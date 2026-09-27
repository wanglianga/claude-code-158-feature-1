package com.wasteoil.inspection;

import com.wasteoil.common.BizException;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.risk.RiskEntry;
import com.wasteoil.risk.RiskEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管抽查服务。抽查不合格会登记异常并将门店列入风险名单。
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final ManifestRepository manifestRepository;
    private final ExceptionService exceptionService;
    private final RiskEntryRepository riskEntryRepository;
    private final EventPublisher eventPublisher;

    public InspectionService(InspectionRepository inspectionRepository,
                             ManifestRepository manifestRepository,
                             ExceptionService exceptionService,
                             RiskEntryRepository riskEntryRepository,
                             EventPublisher eventPublisher) {
        this.inspectionRepository = inspectionRepository;
        this.manifestRepository = manifestRepository;
        this.exceptionService = exceptionService;
        this.riskEntryRepository = riskEntryRepository;
        this.eventPublisher = eventPublisher;
    }

    /** 监管对联单发起抽查 */
    @Transactional
    public Inspection inspect(Long manifestId, String inspector, String result, String note) {
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        Inspection inspection = new Inspection();
        inspection.setManifestId(manifestId);
        inspection.setInspector(inspector);
        inspection.setResult(result);
        inspection.setNote(note);
        Inspection saved = inspectionRepository.save(inspection);

        if ("FAIL".equals(result)) {
            exceptionService.raise(ExceptionType.REGULATORY_INSPECTION, "MANIFEST", manifestId,
                    "监管抽查不合格: " + (note == null ? "" : note));
            // 抽查不合格直接进风险名单
            RiskEntry risk = new RiskEntry();
            risk.setStoreId(manifest.getStoreId());
            risk.setIndicator("INSPECTION_FAIL");
            risk.setDetail("联单 " + manifest.getManifestNo() + " 监管抽查不合格: "
                    + (note == null ? "" : note));
            risk.setScore(80);
            risk.setWindowStart(LocalDate.now());
            risk.setWindowEnd(LocalDate.now());
            riskEntryRepository.save(risk);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("inspectionId", saved.getId());
        payload.put("manifestId", manifestId);
        payload.put("result", result);
        payload.put("inspector", inspector);
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "manifest-" + manifestId, "InspectionRecorded", payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Inspection> listByManifest(Long manifestId) {
        return inspectionRepository.findByManifestId(manifestId);
    }
}
