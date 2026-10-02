package com.wasteoil.weighing;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.appointment.AppointmentRepository;
import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.barrel.BarrelStatus;
import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.Driver;
import com.wasteoil.dispatch.DriverRepository;
import com.wasteoil.dispatch.DispatchStatus;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.supplement.SupplementReview;
import com.wasteoil.supplement.SupplementReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 称重服务：设备直传、司机手动补录与设备离线补录。
 * 称重数据在同一事务内写入电子联单（单一口径），并发布事件到 Kafka。
 * 设备离线补录（OFFLINE_MANUAL）需拍摄秤面照片，联单进入待复核，
 * 复核通过前不允许进入结算。
 */
@Service
public class WeighingService {

    private final WeighingRecordRepository weighingRecordRepository;
    private final WeighingDeviceRepository deviceRepository;
    private final BarrelRepository barrelRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final AppointmentRepository appointmentRepository;
    private final ManifestRepository manifestRepository;
    private final DriverRepository driverRepository;
    private final SupplementReviewRepository supplementReviewRepository;
    private final ExceptionService exceptionService;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public WeighingService(WeighingRecordRepository weighingRecordRepository,
                           WeighingDeviceRepository deviceRepository,
                           BarrelRepository barrelRepository,
                           DispatchOrderRepository dispatchRepository,
                           AppointmentRepository appointmentRepository,
                           ManifestRepository manifestRepository,
                           DriverRepository driverRepository,
                           SupplementReviewRepository supplementReviewRepository,
                           ExceptionService exceptionService,
                           EventPublisher eventPublisher,
                           WasteOilProperties properties) {
        this.weighingRecordRepository = weighingRecordRepository;
        this.deviceRepository = deviceRepository;
        this.barrelRepository = barrelRepository;
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.manifestRepository = manifestRepository;
        this.driverRepository = driverRepository;
        this.supplementReviewRepository = supplementReviewRepository;
        this.exceptionService = exceptionService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 提交一条称重记录。
     *
     * @param source DEVICE（设备直传）/ MANUAL（手动补录，须填原因）/
     *               OFFLINE_MANUAL（蓝牙秤无法连接时离线补录，须填原因并拍摄秤面照片，
     *               联单进入待复核，复核通过后才允许结算）
     */
    @Transactional
    public WeighingRecord submit(Long dispatchId, String barrelCode, BigDecimal weightKg,
                                 String deviceCode, String source, String photoUrl,
                                 String scalePhotoUrl, String handoverName, String note) {
        DispatchOrder dispatch = dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> BizException.notFound("派单"));
        if (dispatch.getStatus() != DispatchStatus.LOADING) {
            throw BizException.conflict("派单未处于装车称重状态（当前 " + dispatch.getStatus() + "）");
        }
        Appointment appointment = appointmentRepository.findById(dispatch.getAppointmentId())
                .orElseThrow(() -> BizException.notFound("预约"));

        boolean manual = "MANUAL".equalsIgnoreCase(source);
        boolean offlineManual = "OFFLINE_MANUAL".equalsIgnoreCase(source);
        WeighingDevice device = null;
        boolean deviceOffline = false;
        if (offlineManual) {
            // 蓝牙秤无法连接：必须说明原因并拍摄秤面照片，且司机未被限制手动补录
            if (note == null || note.isBlank()) {
                throw BizException.badRequest("离线补录必须填写设备离线原因");
            }
            if (scalePhotoUrl == null || scalePhotoUrl.isBlank()) {
                throw BizException.badRequest("离线补录必须拍摄秤面照片");
            }
            Driver driver = driverRepository.findById(dispatch.getDriverId())
                    .orElseThrow(() -> BizException.notFound("司机"));
            if (Boolean.TRUE.equals(driver.getManualRestricted())) {
                throw BizException.conflict(
                        "该司机因频繁设备离线已被限制手动补录，请联系回收公司检查设备后解除限制");
            }
            deviceOffline = true;
        } else if (manual) {
            if (note == null || note.isBlank()) {
                throw BizException.badRequest("手动补录必须填写原因");
            }
        } else {
            if (deviceCode == null || deviceCode.isBlank()) {
                throw BizException.badRequest("设备直传必须携带设备编号");
            }
            device = deviceRepository.findByCode(deviceCode)
                    .orElseThrow(() -> BizException.notFound("称重设备: " + deviceCode));
            if (!device.getStoreId().equals(appointment.getStoreId())) {
                throw BizException.badRequest("称重设备不属于该门店");
            }
            deviceOffline = isOffline(device);
        }

        Barrel barrel = barrelRepository.findByCode(barrelCode)
                .orElseThrow(() -> BizException.notFound("油桶: " + barrelCode));
        if (!barrel.getStoreId().equals(appointment.getStoreId())) {
            throw BizException.badRequest("油桶 " + barrelCode + " 不属于本次回收门店");
        }
        if (barrel.getStatus() != BarrelStatus.AT_STORE) {
            throw BizException.conflict("油桶 " + barrelCode + " 状态为 " + barrel.getStatus() + "，不能装车");
        }
        if (weighingRecordRepository.existsByDispatchIdAndBarrelId(dispatchId, barrel.getId())) {
            throw BizException.conflict("油桶 " + barrelCode + " 本趟已称重，不能重复");
        }
        if (weightKg.compareTo(barrel.getCapacityKg()) > 0) {
            throw BizException.badRequest(String.format(
                    "称重 %.1f kg 超过油桶容量 %.1f kg，请核实", weightKg, barrel.getCapacityKg()));
        }

        WeighingRecord record = new WeighingRecord();
        record.setDispatchId(dispatchId);
        record.setBarrelId(barrel.getId());
        record.setDeviceId(device == null ? null : device.getId());
        record.setWeightKg(weightKg);
        record.setSource(offlineManual ? "OFFLINE_MANUAL" : (manual ? "MANUAL" : "DEVICE"));
        record.setDeviceOffline(deviceOffline);
        record.setPhotoUrl(photoUrl);
        record.setScalePhotoUrl(scalePhotoUrl);
        record.setHandoverName(handoverName);
        record.setNote(note);
        record.setWeighedAt(Instant.now());

        Manifest manifest = manifestRepository.findByDispatchId(dispatchId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        record.setManifestId(manifest.getId());
        WeighingRecord saved = weighingRecordRepository.save(record);

        // 油桶装车
        barrel.setStatus(BarrelStatus.LOADED);
        barrel.setUpdatedAt(Instant.now());
        barrelRepository.save(barrel);

        // 联单聚合（同一事务，保证单一口径）
        manifest.setExitWeightKg(manifest.getExitWeightKg().add(weightKg));
        manifest.setBarrelCountExit(manifest.getBarrelCountExit() + 1);
        if (manual || offlineManual) {
            manifest.setManualWeighCount(manifest.getManualWeighCount() + 1);
        }
        if (deviceOffline) {
            manifest.setOfflineWeighCount(manifest.getOfflineWeighCount() + 1);
        }
        manifest.setUpdatedAt(Instant.now());

        SupplementReview review = null;
        if (offlineManual) {
            // 联单进入待复核：生成/重置复核单，复核通过前不允许进入结算；
            // 同一联单再次离线补录时，原复核结论与人工补录标记一并失效，需重新复核
            manifest.setSupplementReviewStatus("PENDING");
            manifest.setManualSupplementFlag(false);
            review = upsertPendingReview(manifest);
        }
        manifestRepository.save(manifest);

        // 异常登记（信息类，不阻断）
        if (manual) {
            exceptionService.raise(ExceptionType.MANUAL_ENTRY, "MANIFEST", manifest.getId(),
                    "司机手动补录称重: 桶 " + barrelCode + " " + weightKg + " kg，原因: " + note);
        }
        if (deviceOffline && !offlineManual) {
            exceptionService.raise(ExceptionType.DEVICE_OFFLINE, "MANIFEST", manifest.getId(),
                    "称重设备 " + deviceCode + " 离线状态下直传数据: 桶 " + barrelCode);
        }
        if (offlineManual) {
            checkFrequentOffline(dispatch);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("weighingId", saved.getId());
        payload.put("dispatchId", dispatchId);
        payload.put("manifestId", manifest.getId());
        payload.put("barrelCode", barrelCode);
        payload.put("weightKg", weightKg);
        payload.put("source", saved.getSource());
        payload.put("deviceOffline", deviceOffline);
        if (offlineManual) {
            payload.put("driverId", dispatch.getDriverId());
            payload.put("supplementReviewId", review == null ? null : review.getId());
            payload.put("scalePhotoUrl", scalePhotoUrl);
        }
        eventPublisher.publish(KafkaTopics.WEIGHING_EVENTS,
                "manifest-" + manifest.getId(),
                offlineManual ? "OfflineSupplementRecorded" : "WeighingRecorded", payload);
        return saved;
    }

    /** 同一司机频繁设备离线：登记异常工单，要求回收公司检查设备或限制该司机补录 */
    private void checkFrequentOffline(DispatchOrder dispatch) {
        Instant since = Instant.now().minus(properties.getDriverOfflineWindowDays(), ChronoUnit.DAYS);
        long count = weighingRecordRepository.countOfflineSupplementsByDriverSince(
                dispatch.getDriverId(), since);
        if (count >= properties.getDriverOfflineFrequentThreshold()) {
            exceptionService.raise(ExceptionType.DRIVER_OFFLINE_FREQUENT, "DRIVER", dispatch.getDriverId(),
                    String.format("司机近 %d 天已 %d 次设备离线补录（阈值 %d 次），"
                                    + "要求回收公司检查称重设备或限制该司机继续手动补录",
                            properties.getDriverOfflineWindowDays(), count,
                            properties.getDriverOfflineFrequentThreshold()));
        }
    }

    /** 一张联单一条复核单；已有复核单时重置为待复核并累计补录条数 */
    private SupplementReview upsertPendingReview(Manifest manifest) {
        SupplementReview review = supplementReviewRepository.findByManifestId(manifest.getId())
                .orElseGet(() -> {
                    SupplementReview r = new SupplementReview();
                    r.setManifestId(manifest.getId());
                    return r;
                });
        review.setStatus("PENDING");
        review.setSupplementCount(review.getSupplementCount() + 1);
        review.setPhotoClarity(null);
        review.setReviewNote(null);
        review.setReviewer(null);
        review.setReviewedAt(null);
        return supplementReviewRepository.save(review);
    }

    /** 设备心跳上报 */
    @Transactional
    public WeighingDevice heartbeat(String deviceCode) {
        WeighingDevice device = deviceRepository.findByCode(deviceCode)
                .orElseThrow(() -> BizException.notFound("称重设备: " + deviceCode));
        device.setLastHeartbeatAt(Instant.now());
        device.setStatus("ONLINE");
        return deviceRepository.save(device);
    }

    /** 判断设备是否离线（心跳超时） */
    public boolean isOffline(WeighingDevice device) {
        if (device.getLastHeartbeatAt() == null) {
            return true;
        }
        return device.getLastHeartbeatAt().isBefore(
                Instant.now().minus(properties.getDeviceOfflineMinutes(), ChronoUnit.MINUTES));
    }
}
