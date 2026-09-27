package com.wasteoil.appointment;

import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.DispatchStatus;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 回收预约服务：商户按门店提交预约，可临时取消。
 */
@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final StoreRepository storeRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final ManifestRepository manifestRepository;
    private final ExceptionService exceptionService;
    private final EventPublisher eventPublisher;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              StoreRepository storeRepository,
                              DispatchOrderRepository dispatchRepository,
                              ManifestRepository manifestRepository,
                              ExceptionService exceptionService,
                              EventPublisher eventPublisher) {
        this.appointmentRepository = appointmentRepository;
        this.storeRepository = storeRepository;
        this.dispatchRepository = dispatchRepository;
        this.manifestRepository = manifestRepository;
        this.exceptionService = exceptionService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Appointment create(AppointmentRequest req) {
        Store store = storeRepository.findById(req.storeId())
                .orElseThrow(() -> BizException.notFound("门店"));
        if (!"ACTIVE".equals(store.getStatus())) {
            throw BizException.conflict("门店已停用，不能提交预约");
        }
        if (!req.windowEnd().isAfter(req.windowStart())) {
            throw BizException.badRequest("上门时间窗结束时间必须晚于开始时间");
        }
        if (req.windowEnd().isBefore(Instant.now())) {
            throw BizException.badRequest("上门时间窗不能整体落在过去");
        }

        Appointment appointment = new Appointment();
        appointment.setStoreId(store.getId());
        appointment.setBarrelCount(req.barrelCount());
        appointment.setEstimatedWeightKg(req.estimatedWeightKg());
        appointment.setStorageLocation(
                req.storageLocation() == null || req.storageLocation().isBlank()
                        ? store.getStorageLocation() : req.storageLocation());
        appointment.setContactName(req.contactName() == null || req.contactName().isBlank()
                ? store.getContactName() : req.contactName());
        appointment.setContactPhone(req.contactPhone() == null || req.contactPhone().isBlank()
                ? store.getContactPhone() : req.contactPhone());
        appointment.setWindowStart(req.windowStart());
        appointment.setWindowEnd(req.windowEnd());
        appointment.setStatus(AppointmentStatus.PENDING);
        Appointment saved = appointmentRepository.save(appointment);

        publish(saved, "AppointmentCreated");
        return saved;
    }

    /**
     * 商户临时取消预约。已派单的联动取消派单与联单，并登记异常工单。
     */
    @Transactional
    public Appointment cancel(Long appointmentId, String reason) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> BizException.notFound("预约"));
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw BizException.conflict("预约已取消");
        }
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw BizException.conflict("预约已完成，不能取消");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.badRequest("取消必须填写原因");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelReason(reason);
        appointment.setUpdatedAt(Instant.now());

        // 已派单：取消派单与联单，释放车辆
        Optional<DispatchOrder> dispatchOpt = dispatchRepository.findByAppointmentId(appointmentId);
        if (dispatchOpt.isPresent()) {
            DispatchOrder dispatch = dispatchOpt.get();
            if (dispatch.getStatus() != DispatchStatus.COMPLETED
                    && dispatch.getStatus() != DispatchStatus.CANCELLED) {
                dispatch.setStatus(DispatchStatus.CANCELLED);
                dispatch.setUpdatedAt(Instant.now());
                dispatchRepository.save(dispatch);
            }
            manifestRepository.findByDispatchId(dispatch.getId()).ifPresent(manifest -> {
                if (manifest.getStatus() == ManifestStatus.CREATED
                        || manifest.getStatus() == ManifestStatus.IN_TRANSIT) {
                    manifest.setStatus(ManifestStatus.CANCELLED);
                    manifest.setExceptionNote("商户临时取消: " + reason);
                    manifest.setUpdatedAt(Instant.now());
                    manifestRepository.save(manifest);
                }
            });
        }

        // 登记异常：商户临时取消
        exceptionService.raise(ExceptionType.MERCHANT_CANCEL, "APPOINTMENT", appointmentId,
                "商户临时取消预约，原因: " + reason);

        Appointment saved = appointmentRepository.save(appointment);
        publish(saved, "AppointmentCancelled");
        return saved;
    }

    @Transactional(readOnly = true)
    public Appointment get(Long id) {
        return appointmentRepository.findById(id).orElseThrow(() -> BizException.notFound("预约"));
    }

    @Transactional(readOnly = true)
    public List<Appointment> listByStore(Long storeId) {
        return appointmentRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
    }

    @Transactional(readOnly = true)
    public List<Appointment> listPending() {
        return appointmentRepository.findByStatus(AppointmentStatus.PENDING);
    }

    private void publish(Appointment appointment, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("appointmentId", appointment.getId());
        payload.put("storeId", appointment.getStoreId());
        payload.put("barrelCount", appointment.getBarrelCount());
        payload.put("estimatedWeightKg", appointment.getEstimatedWeightKg());
        payload.put("status", appointment.getStatus().name());
        payload.put("operator", AuthContext.accountId());
        eventPublisher.publish(KafkaTopics.APPOINTMENT_EVENTS,
                "appointment-" + appointment.getId(), type, payload);
    }
}
