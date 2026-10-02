package com.wasteoil.supplement;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.appointment.AppointmentRepository;
import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.compliance.ComplianceService;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.Driver;
import com.wasteoil.dispatch.DriverRepository;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线补录复核服务。复核人员核对秤面照片清晰度、油桶数量、历史重量与
 * 司机补录频次后决定放行结算或驳回；通过后给该批联单打上人工补录标记，
 * 监管抽查可优先查看；驳回属于异常补录，扣减司机信用并阻断闭环。
 */
@Service
public class SupplementReviewService {

    private final SupplementReviewRepository reviewRepository;
    private final ManifestRepository manifestRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final AppointmentRepository appointmentRepository;
    private final DriverRepository driverRepository;
    private final BarrelRepository barrelRepository;
    private final ExceptionService exceptionService;
    private final ComplianceService complianceService;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public SupplementReviewService(SupplementReviewRepository reviewRepository,
                                   ManifestRepository manifestRepository,
                                   WeighingRecordRepository weighingRecordRepository,
                                   DispatchOrderRepository dispatchRepository,
                                   AppointmentRepository appointmentRepository,
                                   DriverRepository driverRepository,
                                   BarrelRepository barrelRepository,
                                   ExceptionService exceptionService,
                                   ComplianceService complianceService,
                                   EventPublisher eventPublisher,
                                   WasteOilProperties properties) {
        this.reviewRepository = reviewRepository;
        this.manifestRepository = manifestRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.driverRepository = driverRepository;
        this.barrelRepository = barrelRepository;
        this.exceptionService = exceptionService;
        this.complianceService = complianceService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** 复核队列（默认按创建时间升序，先报先审） */
    @Transactional(readOnly = true)
    public List<SupplementReview> list(String status) {
        if (status == null || status.isBlank()) {
            return reviewRepository.findAllByOrderByCreatedAtDesc();
        }
        return reviewRepository.findByStatusOrderByCreatedAtAsc(status.toUpperCase());
    }

    /**
     * 复核工作台：一次返回复核所需的全部口径——
     * 秤面照片（清晰度核对）、油桶数量（出店 vs 预约）、
     * 补录油桶的历史重量、司机补录频次与信用。
     */
    @Transactional(readOnly = true)
    public ReviewDetail detail(Long reviewId) {
        SupplementReview review = get(reviewId);
        Manifest manifest = manifestRepository.findById(review.getManifestId())
                .orElseThrow(() -> BizException.notFound("电子联单"));
        DispatchOrder dispatch = dispatchRepository.findById(manifest.getDispatchId()).orElse(null);
        Appointment appointment = dispatch == null ? null
                : appointmentRepository.findById(dispatch.getAppointmentId()).orElse(null);

        // 本批离线补录记录（含秤面照片）
        List<WeighingRecord> supplements = weighingRecordRepository
                .findByManifestId(manifest.getId()).stream()
                .filter(w -> "OFFLINE_MANUAL".equals(w.getSource()))
                .toList();

        // 每只补录油桶的历史称重（最近 5 条，供比对本次读数是否异常）
        List<BarrelWeightHistory> histories = supplements.stream().map(w -> {
            Barrel barrel = barrelRepository.findById(w.getBarrelId()).orElse(null);
            String code = barrel == null ? String.valueOf(w.getBarrelId()) : barrel.getCode();
            List<WeighingRecord> recent = weighingRecordRepository
                    .findByBarrelIdOrderByWeighedAtDesc(w.getBarrelId()).stream()
                    .limit(5)
                    .toList();
            return new BarrelWeightHistory(code, recent);
        }).toList();

        // 司机补录频次与信用
        DriverStats driverStats = null;
        if (dispatch != null) {
            Driver driver = driverRepository.findById(dispatch.getDriverId()).orElse(null);
            if (driver != null) {
                Instant since = Instant.now()
                        .minus(properties.getDriverOfflineWindowDays(), ChronoUnit.DAYS);
                driverStats = new DriverStats(driver.getId(), driver.getName(),
                        driver.getCreditScore(), driver.getManualRestricted(),
                        weighingRecordRepository.countOfflineSupplementsByDriverSince(
                                driver.getId(), since),
                        properties.getDriverOfflineWindowDays(),
                        weighingRecordRepository.countManualEntriesByDriver(driver.getId()));
            }
        }

        return new ReviewDetail(review, manifest, supplements,
                manifest.getBarrelCountExit(),
                appointment == null ? null : appointment.getBarrelCount(),
                histories, driverStats);
    }

    /**
     * 复核通过：联单打上人工补录标记；若联单已在待复核状态则恢复闭环进入结算。
     */
    @Transactional
    public SupplementReview approve(Long reviewId, String photoClarity, String note) {
        SupplementReview review = get(reviewId);
        requirePending(review);
        requireClarity(photoClarity);

        review.setStatus("APPROVED");
        finishReview(review, photoClarity, note);

        Manifest manifest = manifestRepository.findById(review.getManifestId())
                .orElseThrow(() -> BizException.notFound("电子联单"));
        manifest.setSupplementReviewStatus("APPROVED");
        // 复核通过：给该批联单打上人工补录标记，监管抽查可优先查看
        manifest.setManualSupplementFlag(true);
        manifest.setUpdatedAt(Instant.now());
        manifestRepository.save(manifest);

        // 联单已走到待复核（合规通过待结算）：恢复闭环，生成结算单
        complianceService.closeAfterReviewApproval(manifest.getId());

        publish(review, manifest, "SupplementReviewApproved");
        return review;
    }

    /**
     * 复核驳回：异常补录，扣减司机信用，联单阻断闭环并登记工单由回收公司处置。
     */
    @Transactional
    public SupplementReview reject(Long reviewId, String photoClarity, String note) {
        SupplementReview review = get(reviewId);
        requirePending(review);
        requireClarity(photoClarity);
        if (note == null || note.isBlank()) {
            throw BizException.badRequest("驳回必须填写原因");
        }

        review.setStatus("REJECTED");
        finishReview(review, photoClarity, note);

        Manifest manifest = manifestRepository.findById(review.getManifestId())
                .orElseThrow(() -> BizException.notFound("电子联单"));
        manifest.setSupplementReviewStatus("REJECTED");
        manifest.setUpdatedAt(Instant.now());
        // 联单已在待复核的，转入异常待处理
        if (manifest.getStatus() == ManifestStatus.PENDING_REVIEW) {
            manifest.setStatus(ManifestStatus.EXCEPTION);
            manifest.setExceptionNote("离线补录复核驳回: " + note);
        }
        manifestRepository.save(manifest);

        // 异常补录影响司机信用
        DispatchOrder dispatch = dispatchRepository.findById(manifest.getDispatchId()).orElse(null);
        if (dispatch != null) {
            Driver driver = driverRepository.findById(dispatch.getDriverId()).orElse(null);
            if (driver != null) {
                driver.setCreditScore(driver.getCreditScore() - properties.getDriverCreditDeductOnReject());
                driverRepository.save(driver);
            }
        }

        // 阻断性工单：回收公司处置（现场核实/复磅）后联单自动重估
        exceptionService.raise(ExceptionType.OFFLINE_SUPPLEMENT_REJECTED, "MANIFEST", manifest.getId(),
                "离线补录复核驳回: " + note);

        publish(review, manifest, "SupplementReviewRejected");
        return review;
    }

    @Transactional(readOnly = true)
    public SupplementReview get(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> BizException.notFound("补录复核单"));
    }

    private void requirePending(SupplementReview review) {
        if (!"PENDING".equals(review.getStatus())) {
            throw BizException.conflict("复核单已处理（当前 " + review.getStatus() + "），不能重复复核");
        }
    }

    private void requireClarity(String photoClarity) {
        if (photoClarity == null
                || !("CLEAR".equalsIgnoreCase(photoClarity) || "BLURRY".equalsIgnoreCase(photoClarity))) {
            throw BizException.badRequest("必须登记秤面照片清晰度（CLEAR / BLURRY）");
        }
    }

    private void finishReview(SupplementReview review, String photoClarity, String note) {
        review.setPhotoClarity(photoClarity.toUpperCase());
        review.setReviewNote(note);
        review.setReviewer(AuthContext.accountId() == null ? "system" : AuthContext.accountId());
        review.setReviewedAt(Instant.now());
        reviewRepository.save(review);
    }

    private void publish(SupplementReview review, Manifest manifest, String eventType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reviewId", review.getId());
        payload.put("manifestId", manifest.getId());
        payload.put("manifestNo", manifest.getManifestNo());
        payload.put("result", review.getStatus());
        payload.put("photoClarity", review.getPhotoClarity());
        payload.put("reviewer", review.getReviewer());
        payload.put("supplementCount", review.getSupplementCount());
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "manifest-" + manifest.getId(), eventType, payload);
    }

    /** 补录油桶的历史称重记录（复核比对用） */
    public record BarrelWeightHistory(String barrelCode, List<WeighingRecord> recentWeighings) {
    }

    /** 司机补录频次与信用（复核比对用） */
    public record DriverStats(Long driverId, String driverName, Integer creditScore,
                              Boolean manualRestricted,
                              long offlineSupplementsInWindow, int windowDays,
                              long totalManualEntries) {
    }

    /** 复核工作台详情 */
    public record ReviewDetail(SupplementReview review,
                               Manifest manifest,
                               List<WeighingRecord> supplements,
                               Integer barrelCountExit,
                               Integer appointmentBarrelCount,
                               List<BarrelWeightHistory> histories,
                               DriverStats driver) {
    }
}
