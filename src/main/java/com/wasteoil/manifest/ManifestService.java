package com.wasteoil.manifest;

import com.wasteoil.common.BizException;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 电子联单服务：联单是全链路单一口径，由系统集中维护，
 * 司机、商户、处置厂、监管端读取同一份数据，不允许各自保存一套口径。
 */
@Service
public class ManifestService {

    private static final DateTimeFormatter NO_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai"));

    private final ManifestRepository manifestRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EventPublisher eventPublisher;

    public ManifestService(ManifestRepository manifestRepository,
                           JdbcTemplate jdbcTemplate,
                           EventPublisher eventPublisher) {
        this.manifestRepository = manifestRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    /** 派单时创建联单（与派单同一事务） */
    @Transactional(propagation = Propagation.REQUIRED)
    public Manifest createForDispatch(Long dispatchId, Long storeId, Long plantId) {
        Manifest manifest = new Manifest();
        manifest.setManifestNo(nextManifestNo());
        manifest.setDispatchId(dispatchId);
        manifest.setStoreId(storeId);
        manifest.setPlantId(plantId);
        manifest.setStatus(ManifestStatus.CREATED);
        Manifest saved = manifestRepository.save(manifest);
        publish(saved, "ManifestCreated");
        return saved;
    }

    @Transactional
    public Manifest transition(Manifest manifest, ManifestStatus target, String eventType) {
        manifest.setStatus(target);
        manifest.setUpdatedAt(Instant.now());
        Manifest saved = manifestRepository.save(manifest);
        publish(saved, eventType);
        return saved;
    }

    @Transactional(readOnly = true)
    public Manifest get(Long id) {
        return manifestRepository.findById(id).orElseThrow(() -> BizException.notFound("电子联单"));
    }

    @Transactional(readOnly = true)
    public Manifest getByDispatch(Long dispatchId) {
        return manifestRepository.findByDispatchId(dispatchId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
    }

    private String nextManifestNo() {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('manifest_no_seq')", Long.class);
        return "M" + NO_DATE_FMT.format(Instant.now()) + "-" + String.format("%04d", seq);
    }

    public void publish(Manifest manifest, String eventType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("manifestId", manifest.getId());
        payload.put("manifestNo", manifest.getManifestNo());
        payload.put("dispatchId", manifest.getDispatchId());
        payload.put("storeId", manifest.getStoreId());
        payload.put("plantId", manifest.getPlantId());
        payload.put("status", manifest.getStatus().name());
        payload.put("exitWeightKg", manifest.getExitWeightKg());
        payload.put("arrivalWeightKg", manifest.getArrivalWeightKg());
        eventPublisher.publish(KafkaTopics.MANIFEST_EVENTS,
                "manifest-" + manifest.getId(), eventType, payload);
    }
}
