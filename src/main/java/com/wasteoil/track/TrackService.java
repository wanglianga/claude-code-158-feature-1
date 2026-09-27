package com.wasteoil.track;

import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.DispatchStatus;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运输轨迹服务：轨迹点上传与轨迹缺失检测。
 */
@Service
public class TrackService {

    private final TrackPointRepository trackPointRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final ExceptionService exceptionService;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public TrackService(TrackPointRepository trackPointRepository,
                        DispatchOrderRepository dispatchRepository,
                        ExceptionService exceptionService,
                        EventPublisher eventPublisher,
                        WasteOilProperties properties) {
        this.trackPointRepository = trackPointRepository;
        this.dispatchRepository = dispatchRepository;
        this.exceptionService = exceptionService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** 批量上传轨迹点 */
    @Transactional
    public int upload(Long dispatchId, List<TrackPointInput> points) {
        DispatchOrder dispatch = dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> BizException.notFound("派单"));
        if (dispatch.getStatus() != DispatchStatus.EN_ROUTE
                && dispatch.getStatus() != DispatchStatus.IN_TRANSIT
                && dispatch.getStatus() != DispatchStatus.ARRIVED
                && dispatch.getStatus() != DispatchStatus.LOADING) {
            throw BizException.conflict("派单状态 " + dispatch.getStatus() + " 不接受轨迹上传");
        }
        List<TrackPoint> entities = points.stream().map(p -> {
            TrackPoint t = new TrackPoint();
            t.setDispatchId(dispatchId);
            t.setLat(p.lat());
            t.setLng(p.lng());
            t.setRecordedAt(p.recordedAt() == null ? Instant.now() : p.recordedAt());
            return t;
        }).toList();
        trackPointRepository.saveAll(entities);

        Map<String, Object> payload = new HashMap<>();
        payload.put("dispatchId", dispatchId);
        payload.put("pointCount", entities.size());
        eventPublisher.publish(KafkaTopics.TRACK_EVENTS, "dispatch-" + dispatchId,
                "TrackPointsUploaded", payload);
        return entities.size();
    }

    @Transactional(readOnly = true)
    public List<TrackPoint> trace(Long dispatchId) {
        return trackPointRepository.findByDispatchIdOrderByRecordedAtAsc(dispatchId);
    }

    /**
     * 定时扫描：运输中的派单若长时间无轨迹点，登记轨迹缺失异常并在联单上标记。
     */
    @Transactional
    public int scanMissingTracks() {
        Instant threshold = Instant.now()
                .minus(properties.getTrackMissingMinutes(), ChronoUnit.MINUTES);
        int raised = 0;
        for (DispatchOrder d : dispatchRepository.findByStatus(DispatchStatus.IN_TRANSIT)) {
            // 起运时间早于阈值，且阈值时间窗内没有任何轨迹点
            if (d.getLoadDoneAt() != null && d.getLoadDoneAt().isBefore(threshold)
                    && !trackPointRepository.existsByDispatchIdAndRecordedAtAfter(d.getId(), threshold)) {
                exceptionService.raise(ExceptionType.TRACK_MISSING, "DISPATCH", d.getId(),
                        "运输途中超过 " + properties.getTrackMissingMinutes() + " 分钟无轨迹点");
                raised++;
            }
        }
        return raised;
    }

    public record TrackPointInput(java.math.BigDecimal lat, java.math.BigDecimal lng,
                                  Instant recordedAt) {
    }
}
