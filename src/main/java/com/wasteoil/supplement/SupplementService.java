package com.wasteoil.supplement;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.appointment.AppointmentRepository;
import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.barrel.BarrelStatus;
import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.DispatchStatus;
import com.wasteoil.dispatch.Driver;
import com.wasteoil.dispatch.DriverRepository;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionTicket;
import com.wasteoil.exception.ExceptionTicketRepository;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.weighing.WeighingDevice;
import com.wasteoil.weighing.WeighingDeviceRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线补录与人工复核服务。
 *
 * <p>司机到店蓝牙秤无法连接时：拍摄秤面照片 + 手动录入重量提交补录批次，
 * 联单进入 {@link ManifestStatus#PENDING_REVIEW}，复核通过前不得起运与结算。
 *
 * <p>复核人员核对照片清晰度、油桶数量、历史重量与司机补录频次后决策：
 * 通过则重量采信并给批次联单打上人工补录标记；驳回则重量回滚、登记异常并扣减司机信用。
 *
 * <p>同一司机窗口内频繁离线补录将被限制继续手动补录；同一设备频繁离线则生成
 * {@link ExceptionType#DEVICE_CHECK} 工单，要求回收公司检查设备。
 */
@Slf4j
@Service
public class SupplementService {

    private static final DateTimeFormatter NO_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai"));

    private final OfflineSupplementRepository supplementRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final AppointmentRepository appointmentRepository;
    private final DriverRepository driverRepository;
    private final WeighingDeviceRepository deviceRepository;
    private final BarrelRepository barrelRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final ManifestRepository manifestRepository;
    private final ExceptionService exceptionService;
    private final ExceptionTicketRepository exceptionTicketRepository;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public SupplementService(OfflineSupplementRepository supplementRepository,
                             DispatchOrderRepository dispatchRepository,
                             AppointmentRepository appointmentRepository,
                             DriverRepository driverRepository,
                             WeighingDeviceRepository deviceRepository,
                             BarrelRepository barrelRepository,
                             WeighingRecordRepository weighingRecordRepository,
                             ManifestRepository manifestRepository,
                             ExceptionService exceptionService,
                             ExceptionTicketRepository exceptionTicketRepository,
                             EventPublisher eventPublisher,
                             WasteOilProperties properties,
                             JdbcTemplate jdbcTemplate) {
        this.supplementRepository = supplementRepository;
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.driverRepository = driverRepository;
        this.deviceRepository = deviceRepository;
        this.barrelRepository = barrelRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.manifestRepository = manifestRepository;
        this.exceptionService = exceptionService;
        this.exceptionTicketRepository = exceptionTicketRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 司机提交离线补录 ====================

    /**
     * 司机在蓝牙秤离线时提交补录批次：秤面照片 + 逐桶手录重量。
     * 联单进入待复核；重量在复核通过前不计入出店重量。
     */
    @Transactional
    public OfflineSupplement submit(Long dispatchId, String deviceCode, List<SupplementItem> items,
                                    String photoCsv, String reason) {
        DispatchOrder dispatch = dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> BizException.notFound("派单"));
        if (dispatch.getStatus() != DispatchStatus.LOADING) {
            throw BizException.conflict("派单未处于装车称重状态（当前 " + dispatch.getStatus() + "）");
        }
        Driver driver = driverRepository.findById(dispatch.getDriverId())
                .orElseThrow(() -> BizException.notFound("司机"));
        if (Boolean.TRUE.equals(driver.getManualBlocked())) {
            throw BizException.conflict("该司机补录频次异常，已被限制手动补录，请联系回收公司处理");
        }
        if (driver.getCreditScore() < properties.getCreditBlockThreshold()) {
            throw BizException.conflict(String.format(
                    "司机信用分 %d 低于阈值 %d，已被限制手动补录",
                    driver.getCreditScore(), properties.getCreditBlockThreshold()));
        }

        Appointment appointment = appointmentRepository.findById(dispatch.getAppointmentId())
                .orElseThrow(() -> BizException.notFound("预约"));
        if (deviceCode == null || deviceCode.isBlank()) {
            throw BizException.badRequest("离线补录必须携带无法连接的蓝牙秤设备编号");
        }
        WeighingDevice device = deviceRepository.findByCode(deviceCode)
                .orElseThrow(() -> BizException.notFound("称重设备: " + deviceCode));
        if (!device.getStoreId().equals(appointment.getStoreId())) {
            throw BizException.badRequest("称重设备不属于该门店");
        }
        if (!isOffline(device)) {
            throw BizException.conflict("设备 " + deviceCode + " 在线，应使用蓝牙秤直传称重，不能走离线补录");
        }
        if (photoCsv == null || photoCsv.isBlank()) {
            throw BizException.badRequest("离线补录必须拍摄秤面照片");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.badRequest("离线补录必须填写设备离线情况说明");
        }
        if (items == null || items.isEmpty()) {
            throw BizException.badRequest("补录油桶与重量不能为空");
        }

        List<String> codes = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        List<Barrel> barrels = new ArrayList<>();
        for (SupplementItem item : items) {
            if (item.barrelCode() == null || item.barrelCode().isBlank()
                    || item.weightKg() == null) {
                throw BizException.badRequest("补录明细的桶码与重量不能为空");
            }
            if (item.weightKg().signum() <= 0) {
                throw BizException.badRequest("补录重量必须大于 0");
            }
            Barrel barrel = barrelRepository.findByCode(item.barrelCode())
                    .orElseThrow(() -> BizException.notFound("油桶: " + item.barrelCode()));
            if (!barrel.getStoreId().equals(appointment.getStoreId())) {
                throw BizException.badRequest("油桶 " + item.barrelCode() + " 不属于本次回收门店");
            }
            if (barrel.getStatus() != BarrelStatus.AT_STORE) {
                throw BizException.conflict("油桶 " + item.barrelCode() + " 状态为 "
                        + barrel.getStatus() + "，不能装车");
            }
            if (weighingRecordRepository.existsByDispatchIdAndBarrelId(dispatchId, barrel.getId())) {
                throw BizException.conflict("油桶 " + item.barrelCode() + " 本趟已称重，不能重复");
            }
            if (item.weightKg().compareTo(barrel.getCapacityKg()) > 0) {
                throw BizException.badRequest(String.format(
                        "补录重量 %.1f kg 超过油桶容量 %.1f kg", item.weightKg(), barrel.getCapacityKg()));
            }
            if (codes.contains(item.barrelCode())) {
                throw BizException.badRequest("同一桶码在补录批次中重复: " + item.barrelCode());
            }
            codes.add(item.barrelCode());
            barrels.add(barrel);
            total = total.add(item.weightKg());
        }

        Manifest manifest = manifestRepository.findByDispatchId(dispatchId)
                .orElseThrow(() -> BizException.notFound("电子联单"));

        OfflineSupplement supplement = new OfflineSupplement();
        supplement.setSupplementNo(nextSupplementNo());
        supplement.setDispatchId(dispatchId);
        supplement.setManifestId(manifest.getId());
        supplement.setDriverId(driver.getId());
        supplement.setDeviceId(device.getId());
        supplement.setStoreId(appointment.getStoreId());
        supplement.setDeclaredWeightKg(total);
        supplement.setDeclaredBarrelCount(codes.size());
        supplement.setBarrelCodes(String.join(",", codes));
        supplement.setPhotoUrls(photoCsv);
        supplement.setReason(reason);
        supplement.setStatus(SupplementStatus.PENDING_REVIEW);
        OfflineSupplement saved = supplementRepository.save(supplement);

        // 逐桶生成"待复核"称重明细：复核前不计入联单出店重量与桶数
        for (int i = 0; i < items.size(); i++) {
            SupplementItem item = items.get(i);
            WeighingRecord record = new WeighingRecord();
            record.setDispatchId(dispatchId);
            record.setManifestId(manifest.getId());
            record.setBarrelId(barrels.get(i).getId());
            record.setDeviceId(device.getId());
            record.setWeightKg(item.weightKg());
            record.setSource("MANUAL");
            record.setDeviceOffline(true);
            record.setReviewStatus("PENDING");
            record.setSupplementId(saved.getId());
            record.setPhotoUrl(photoCsv.split(",")[0]);
            record.setHandoverName(item.handoverName());
            record.setNote("离线补录: " + reason);
            record.setWeighedAt(Instant.now());
            weighingRecordRepository.save(record);

            barrels.get(i).setStatus(BarrelStatus.LOADED);
            barrels.get(i).setUpdatedAt(Instant.now());
            barrelRepository.save(barrels.get(i));
        }

        // 联单进入待复核（同一事务，单一口径）
        manifest.setPendingReviewCount(manifest.getPendingReviewCount() + 1);
        manifest.setStatus(ManifestStatus.PENDING_REVIEW);
        manifest.setUpdatedAt(Instant.now());
        manifestRepository.save(manifest);

        // 设备离线留痕（信息类，不阻断）
        exceptionService.raise(ExceptionType.DEVICE_OFFLINE, "MANIFEST", manifest.getId(),
                "蓝牙秤 " + deviceCode + " 无法连接，司机提交离线补录批次 "
                        + saved.getSupplementNo() + "，" + codes.size() + " 桶/" + total + " kg");

        applyFrequentOfflineRules(saved, driver, device);

        Map<String, Object> payload = new HashMap<>();
        payload.put("supplementId", saved.getId());
        payload.put("supplementNo", saved.getSupplementNo());
        payload.put("dispatchId", dispatchId);
        payload.put("manifestId", manifest.getId());
        payload.put("driverId", driver.getId());
        payload.put("deviceCode", deviceCode);
        payload.put("declaredWeightKg", total);
        payload.put("barrelCount", codes.size());
        eventPublisher.publish(KafkaTopics.WEIGHING_EVENTS,
                "manifest-" + manifest.getId(), "OfflineSupplementSubmitted", payload);
        return saved;
    }

    /**
     * 频繁离线管控：
     * 设备维度达到阈值 → 生成 DEVICE_CHECK 工单要求回收公司检查设备；
     * 司机维度（自上次设备核查起）达到上限 → 限制该司机继续手动补录。
     */
    private void applyFrequentOfflineRules(OfflineSupplement supplement, Driver driver,
                                           WeighingDevice device) {
        Instant windowStart = Instant.now().minus(properties.getSupplementWindowDays(),
                java.time.temporal.ChronoUnit.DAYS);

        long deviceCount = supplementRepository.countByDeviceIdAndCreatedAtAfter(
                device.getId(), windowStart);
        if (deviceCount >= properties.getSupplementDeviceCheckThreshold()) {
            exceptionService.raise(ExceptionType.DEVICE_CHECK, "DEVICE", device.getId(),
                    String.format("蓝牙秤 %s 近 %d 天已发生 %d 次离线补录，要求回收公司检查设备",
                            device.getCode(), properties.getSupplementWindowDays(), deviceCount));
        }

        Instant driverWindowStart = driverWindowStart(driver);
        long driverCount = supplementRepository.countByDriverIdAndCreatedAtAfter(
                driver.getId(), driverWindowStart);
        if (driverCount >= properties.getSupplementDriverLimit()
                && !Boolean.TRUE.equals(driver.getManualBlocked())) {
            driver.setManualBlocked(true);
            driverRepository.save(driver);
            log.warn("司机 {} 自上次设备核查以来离线补录 {} 次，已限制继续手动补录",
                    driver.getId(), driverCount);
            exceptionService.raise(ExceptionType.DEVICE_CHECK, "DRIVER", driver.getId(),
                    String.format("司机 %s 离线补录 %d 次，已限制继续手动补录，待回收公司核查设备后解除",
                            driver.getName(), driverCount));
        }
    }

    /** 司机补录频次窗口：固定窗口与上次设备核查时间中取较晚者，核查后频次重新起算 */
    private Instant driverWindowStart(Driver driver) {
        Instant fixed = Instant.now().minus(properties.getSupplementWindowDays(),
                java.time.temporal.ChronoUnit.DAYS);
        Instant lastCheck = driver.getLastDeviceCheckAt();
        return lastCheck != null && lastCheck.isAfter(fixed) ? lastCheck : fixed;
    }

    // ==================== 复核决策 ====================

    /**
     * 复核人员决策。APPROVED 要求照片清晰且油桶数量核对相符；
     * REJECTED 必须填写复核意见，回滚补录重量并扣减司机信用。
     */
    @Transactional
    public OfflineSupplement review(Long supplementId, String decision, boolean photoClear,
                                    boolean barrelCountMatch, String comment) {
        OfflineSupplement supplement = supplementRepository.findById(supplementId)
                .orElseThrow(() -> BizException.notFound("离线补录单"));
        if (supplement.getStatus() != SupplementStatus.PENDING_REVIEW) {
            throw BizException.conflict("补录单已复核（当前 " + supplement.getStatus() + "），不能重复复核");
        }
        boolean approve = "APPROVED".equalsIgnoreCase(decision);
        if (!approve && !"REJECTED".equalsIgnoreCase(decision)) {
            throw BizException.badRequest("复核决策只能是 APPROVED 或 REJECTED");
        }
        if (approve && (!photoClear || !barrelCountMatch)) {
            throw BizException.badRequest("照片不清晰或油桶数量核对不符的补录不能通过，请驳回处理");
        }
        if (!approve && (comment == null || comment.isBlank())) {
            throw BizException.badRequest("驳回必须填写复核意见");
        }

        supplement.setPhotoClear(photoClear);
        supplement.setBarrelCountMatch(barrelCountMatch);
        supplement.setReviewComment(comment);
        supplement.setReviewer(com.wasteoil.common.AuthContext.accountId() == null
                ? "reviewer" : com.wasteoil.common.AuthContext.accountId());
        supplement.setReviewedAt(Instant.now());

        Manifest manifest = manifestRepository.findById(supplement.getManifestId())
                .orElseThrow(() -> BizException.notFound("电子联单"));
        List<WeighingRecord> records = weighingRecordRepository
                .findBySupplementId(supplement.getId());

        if (approve) {
            supplement.setStatus(SupplementStatus.APPROVED);
            // 重量此刻才采信：聚合出店重量/桶数，称重明细转 APPROVED
            for (WeighingRecord r : records) {
                r.setReviewStatus("APPROVED");
                manifest.setExitWeightKg(manifest.getExitWeightKg().add(r.getWeightKg()));
                manifest.setBarrelCountExit(manifest.getBarrelCountExit() + 1);
                manifest.setManualWeighCount(manifest.getManualWeighCount() + 1);
                manifest.setOfflineWeighCount(manifest.getOfflineWeighCount() + 1);
                weighingRecordRepository.save(r);
            }
            // 该批联单打上人工补录标记，供后续监管抽查优先查看
            manifest.setManualSupplementFlag(true);
            decrementPending(manifest);
            log.info("离线补录 {} 复核通过，采信 {} 桶/{} kg",
                    supplement.getSupplementNo(), records.size(), supplement.getDeclaredWeightKg());
        } else {
            supplement.setStatus(SupplementStatus.REJECTED);
            // 回滚：删除待复核称重明细，油桶退回在店状态
            for (WeighingRecord r : records) {
                Barrel barrel = barrelRepository.findById(r.getBarrelId()).orElseThrow();
                barrel.setStatus(BarrelStatus.AT_STORE);
                barrel.setUpdatedAt(Instant.now());
                barrelRepository.save(barrel);
                weighingRecordRepository.delete(r);
            }
            decrementPending(manifest);

            // 异常补录影响司机信用
            Driver driver = driverRepository.findById(supplement.getDriverId()).orElseThrow();
            int newScore = Math.max(0, driver.getCreditScore() - properties.getSupplementRejectCreditPenalty());
            driver.setCreditScore(newScore);
            if (newScore < properties.getCreditBlockThreshold()) {
                driver.setManualBlocked(true);
            }
            driverRepository.save(driver);

            exceptionService.raise(ExceptionType.SUPPLEMENT_REJECTED, "MANIFEST", manifest.getId(),
                    String.format("离线补录 %s 复核驳回: %s；司机 %s 信用分降至 %d",
                            supplement.getSupplementNo(), comment, driver.getName(), newScore));
            log.warn("离线补录 {} 复核驳回，扣减司机 {} 信用分至 {}",
                    supplement.getSupplementNo(), driver.getId(), newScore);
        }

        manifest.setUpdatedAt(Instant.now());
        manifestRepository.save(manifest);
        OfflineSupplement saved = supplementRepository.save(supplement);

        Map<String, Object> payload = new HashMap<>();
        payload.put("supplementId", saved.getId());
        payload.put("supplementNo", saved.getSupplementNo());
        payload.put("manifestId", manifest.getId());
        payload.put("decision", saved.getStatus().name());
        payload.put("photoClear", photoClear);
        payload.put("barrelCountMatch", barrelCountMatch);
        payload.put("reviewer", saved.getReviewer());
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "manifest-" + manifest.getId(), "OfflineSupplementReviewed", payload);
        return saved;
    }

    /** 待复核计数减一；归零时联单退出待复核，回到装车态继续流程 */
    private void decrementPending(Manifest manifest) {
        int remaining = Math.max(0, manifest.getPendingReviewCount() - 1);
        manifest.setPendingReviewCount(remaining);
        if (remaining == 0 && manifest.getStatus() == ManifestStatus.PENDING_REVIEW) {
            manifest.setStatus(ManifestStatus.CREATED);
        }
    }

    // ==================== 回收公司解除补录限制（核查设备后） ====================

    @Transactional
    public DriverProfile unblockDriver(Long driverId, String comment) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> BizException.notFound("司机"));
        driver.setManualBlocked(false);
        driver.setLastDeviceCheckAt(Instant.now());
        driverRepository.save(driver);
        // 解除留痕：直接登记为已处理，不产生 OPEN 工单以免影响后续频繁离线预警
        ExceptionTicket ticket = new ExceptionTicket();
        ticket.setType(ExceptionType.DEVICE_CHECK);
        ticket.setRefType("DRIVER");
        ticket.setRefId(driverId);
        ticket.setDescription("回收公司完成设备核查，解除司机 " + driver.getName()
                + " 的手动补录限制: " + (comment == null ? "" : comment));
        ticket.setBlocking(false);
        ticket.setStatus("RESOLVED");
        ticket.setHandler(com.wasteoil.common.AuthContext.accountId() == null
                ? "recycler" : com.wasteoil.common.AuthContext.accountId());
        ticket.setResolution(comment);
        ticket.setHandledAt(Instant.now());
        exceptionTicketRepository.save(ticket);
        return buildDriverProfile(driver);
    }

    /** 司机补录画像：信用分、限录状态、窗口内补录/驳回频次、未处理设备检查工单 */
    @Transactional(readOnly = true)
    public DriverProfile driverProfile(Long driverId) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> BizException.notFound("司机"));
        return buildDriverProfile(driver);
    }

    private DriverProfile buildDriverProfile(Driver driver) {
        Instant windowStart = driverWindowStart(driver);
        List<OfflineSupplement> history =
                supplementRepository.findByDriverIdAndCreatedAtAfter(driver.getId(), windowStart);
        long rejected = history.stream()
                .filter(x -> x.getStatus() == SupplementStatus.REJECTED).count();
        long openCheckTickets = exceptionTicketRepository.findByRefTypeAndRefId("DRIVER", driver.getId())
                .stream().filter(t -> t.getType() == ExceptionType.DEVICE_CHECK && "OPEN".equals(t.getStatus()))
                .count();
        return new DriverProfile(driver.getId(), driver.getName(), driver.getCreditScore(),
                driver.getManualBlocked(), properties.getSupplementWindowDays(),
                history.size(), rejected, openCheckTickets);
    }

    // ==================== 定时/主动扫描：频繁离线设备与司机 ====================

    /**
     * 扫描统计窗口内频繁离线的设备与司机，生成检查工单/限制补录（幂等）。
     * 返回新触达阈值的对象数。
     */
    @Transactional
    public int scanFrequentOffline() {
        Instant windowStart = Instant.now().minus(properties.getSupplementWindowDays(),
                java.time.temporal.ChronoUnit.DAYS);
        int touched = 0;
        for (WeighingDevice device : deviceRepository.findAll()) {
            if (supplementRepository.countByDeviceIdAndCreatedAtAfter(device.getId(), windowStart)
                    >= properties.getSupplementDeviceCheckThreshold()) {
                exceptionService.raise(ExceptionType.DEVICE_CHECK, "DEVICE", device.getId(),
                        String.format("定时扫描：蓝牙秤 %s 近 %d 天离线补录频繁，要求检查设备",
                                device.getCode(), properties.getSupplementWindowDays()));
                touched++;
            }
        }
        for (Driver driver : driverRepository.findAll()) {
            long count = supplementRepository.countByDriverIdAndCreatedAtAfter(
                    driver.getId(), driverWindowStart(driver));
            if (count >= properties.getSupplementDriverLimit() && !Boolean.TRUE.equals(driver.getManualBlocked())) {
                driver.setManualBlocked(true);
                driverRepository.save(driver);
                exceptionService.raise(ExceptionType.DEVICE_CHECK, "DRIVER", driver.getId(),
                        String.format("定时扫描：司机 %s 近 %d 天离线补录 %d 次，限制继续手动补录",
                                driver.getName(), properties.getSupplementWindowDays(), count));
                touched++;
            }
        }
        return touched;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<OfflineSupplement> list(String status) {
        if (status == null || status.isBlank()) {
            return supplementRepository.findAll();
        }
        return supplementRepository.findByStatusOrderByCreatedAtAsc(
                SupplementStatus.valueOf(status.toUpperCase()));
    }

    @Transactional(readOnly = true)
    public OfflineSupplement get(Long id) {
        return supplementRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("离线补录单"));
    }

    /** 复核台详情：补录单 + 照片/桶数核对项 + 历史重量 + 司机补录频次与信用。 */
    @Transactional(readOnly = true)
    public SupplementReviewView reviewView(Long supplementId) {
        OfflineSupplement s = get(supplementId);
        Driver driver = driverRepository.findById(s.getDriverId()).orElseThrow();
        WeighingDevice device = deviceRepository.findById(s.getDeviceId()).orElseThrow();
        DispatchOrder dispatch = dispatchRepository.findById(s.getDispatchId()).orElseThrow();
        Appointment appointment = appointmentRepository.findById(dispatch.getAppointmentId()).orElse(null);
        Manifest manifest = manifestRepository.findById(s.getManifestId()).orElseThrow();

        Instant windowStart = driverWindowStart(driver);
        List<OfflineSupplement> driverHistory =
                supplementRepository.findByDriverIdAndCreatedAtAfter(driver.getId(), windowStart);
        long totalCount = driverHistory.size();
        long rejectedCount = driverHistory.stream()
                .filter(x -> x.getStatus() == SupplementStatus.REJECTED).count();

        // 历史重量：申报各桶的既往称重 + 门店历史每桶均重
        List<BarrelHistory> barrelHistories = new ArrayList<>();
        for (String code : s.getBarrelCodes().split(",")) {
            Barrel barrel = barrelRepository.findByCode(code).orElse(null);
            if (barrel == null) {
                continue;
            }
            List<BigDecimal> past = weighingRecordRepository
                    .findByBarrelIdOrderByWeighedAtDesc(barrel.getId()).stream()
                    .filter(w -> !"PENDING".equals(w.getReviewStatus()))
                    .map(WeighingRecord::getWeightKg)
                    .toList();
            BigDecimal avg = past.isEmpty() ? null
                    : past.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(past.size()), 2, RoundingMode.HALF_UP);
            barrelHistories.add(new BarrelHistory(code, past, avg));
        }
        List<Manifest> storeManifests = manifestRepository
                .findByStoreIdAndCreatedAtBetween(s.getStoreId(), windowStart, Instant.now());
        BigDecimal storeAvgPerBarrel = BigDecimal.ZERO;
        int counted = 0;
        for (Manifest m : storeManifests) {
            if (m.getBarrelCountExit() != null && m.getBarrelCountExit() > 0) {
                storeAvgPerBarrel = storeAvgPerBarrel.add(
                        m.getExitWeightKg().divide(BigDecimal.valueOf(m.getBarrelCountExit()),
                                2, RoundingMode.HALF_UP));
                counted++;
            }
        }
        if (counted > 0) {
            storeAvgPerBarrel = storeAvgPerBarrel.divide(BigDecimal.valueOf(counted),
                    2, RoundingMode.HALF_UP);
        }
        BigDecimal declaredAvgPerBarrel = s.getDeclaredWeightKg()
                .divide(BigDecimal.valueOf(s.getDeclaredBarrelCount()), 2, RoundingMode.HALF_UP);

        return new SupplementReviewView(s, driver, device.getCode(), appointment, manifest,
                List.of(s.getPhotoUrls().split(",")), totalCount, rejectedCount,
                properties.getSupplementWindowDays(), barrelHistories,
                storeAvgPerBarrel, declaredAvgPerBarrel);
    }

    private boolean isOffline(WeighingDevice device) {
        if (device.getLastHeartbeatAt() == null) {
            return true;
        }
        return device.getLastHeartbeatAt().isBefore(Instant.now()
                .minus(properties.getDeviceOfflineMinutes(), java.time.temporal.ChronoUnit.MINUTES));
    }

    private String nextSupplementNo() {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('supplement_no_seq')", Long.class);
        return "S" + NO_DATE_FMT.format(Instant.now()) + "-" + String.format("%04d", seq);
    }

    /** 补录明细：桶码 + 手录重量 + 交接人 */
    public record SupplementItem(String barrelCode, BigDecimal weightKg, String handoverName) {
    }

    /** 单桶历史称重 */
    public record BarrelHistory(String barrelCode, List<BigDecimal> pastWeights, BigDecimal avgWeightKg) {
    }

    /** 复核台视图 */
    public record SupplementReviewView(
            OfflineSupplement supplement,
            Driver driver,
            String deviceCode,
            Appointment appointment,
            Manifest manifest,
            List<String> photoUrls,
            long windowSupplementCount,
            long windowRejectedCount,
            int windowDays,
            List<BarrelHistory> barrelHistories,
            BigDecimal storeAvgWeightPerBarrel,
            BigDecimal declaredAvgWeightPerBarrel) {
    }

    /** 司机补录画像（复核与管控参考） */
    public record DriverProfile(Long driverId, String name, Integer creditScore, Boolean manualBlocked,
                                int windowDays, long windowSupplementCount, long windowRejectedCount,
                                long openDeviceCheckTickets) {
    }
}
