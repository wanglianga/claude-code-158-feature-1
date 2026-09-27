package com.wasteoil.dispute;

import com.wasteoil.common.BizException;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.settlement.SettlementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户重量质疑：质疑期间联单进入 DISPUTED、结算冻结；
 * 解决后按协商重量调整结算，联单回到 CLOSED。
 */
@Service
public class DisputeService {

    private final DisputeRepository disputeRepository;
    private final ManifestRepository manifestRepository;
    private final StoreRepository storeRepository;
    private final SettlementService settlementService;
    private final ExceptionService exceptionService;
    private final EventPublisher eventPublisher;

    public DisputeService(DisputeRepository disputeRepository,
                          ManifestRepository manifestRepository,
                          StoreRepository storeRepository,
                          SettlementService settlementService,
                          ExceptionService exceptionService,
                          EventPublisher eventPublisher) {
        this.disputeRepository = disputeRepository;
        this.manifestRepository = manifestRepository;
        this.storeRepository = storeRepository;
        this.settlementService = settlementService;
        this.exceptionService = exceptionService;
        this.eventPublisher = eventPublisher;
    }

    /** 商户对联单重量提出质疑 */
    @Transactional
    public Dispute raise(Long manifestId, String reason) {
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        if (manifest.getStatus() != ManifestStatus.CLOSED
                && manifest.getStatus() != ManifestStatus.VERIFIED) {
            throw BizException.conflict("联单状态为 " + manifest.getStatus() + "，不能发起质疑");
        }
        Store store = storeRepository.findById(manifest.getStoreId())
                .orElseThrow(() -> BizException.notFound("门店"));

        Dispute dispute = new Dispute();
        dispute.setManifestId(manifestId);
        dispute.setMerchantId(store.getMerchantId());
        dispute.setReason(reason);
        Dispute saved = disputeRepository.save(dispute);

        manifest.setStatus(ManifestStatus.DISPUTED);
        manifest.setUpdatedAt(Instant.now());
        manifestRepository.save(manifest);

        settlementService.freeze(manifestId);
        exceptionService.raise(ExceptionType.WEIGHT_DISPUTE, "MANIFEST", manifestId,
                "商户质疑重量: " + reason);

        publish(saved, "DisputeRaised");
        return saved;
    }

    /** 回收公司/监管协调解决质疑 */
    @Transactional
    public Dispute resolve(Long disputeId, String resolution, BigDecimal adjustedWeightKg) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("质疑单"));
        if (!"OPEN".equals(dispute.getStatus())) {
            throw BizException.conflict("质疑已解决，不能重复处理");
        }
        dispute.setStatus("RESOLVED");
        dispute.setResolution(resolution);
        dispute.setAdjustedWeightKg(adjustedWeightKg);
        dispute.setResolvedAt(Instant.now());
        Dispute saved = disputeRepository.save(dispute);

        // 调整结算并恢复确认，联单回到闭环
        settlementService.adjustAndConfirm(dispute.getManifestId(), adjustedWeightKg);
        Manifest manifest = manifestRepository.findById(dispute.getManifestId()).orElseThrow();
        manifest.setStatus(ManifestStatus.CLOSED);
        manifest.setUpdatedAt(Instant.now());
        manifestRepository.save(manifest);

        publish(saved, "DisputeResolved");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Dispute> list(String status) {
        if (status == null || status.isBlank()) {
            return disputeRepository.findAll();
        }
        return disputeRepository.findByStatus(status);
    }

    private void publish(Dispute dispute, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("disputeId", dispute.getId());
        payload.put("manifestId", dispute.getManifestId());
        payload.put("merchantId", dispute.getMerchantId());
        payload.put("status", dispute.getStatus());
        eventPublisher.publish(KafkaTopics.MANIFEST_EVENTS,
                "manifest-" + dispute.getManifestId(), type, payload);
    }
}
