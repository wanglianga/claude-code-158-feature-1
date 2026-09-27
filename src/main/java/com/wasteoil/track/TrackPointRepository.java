package com.wasteoil.track;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {

    List<TrackPoint> findByDispatchIdOrderByRecordedAtAsc(Long dispatchId);

    long countByDispatchId(Long dispatchId);

    boolean existsByDispatchIdAndRecordedAtAfter(Long dispatchId, Instant after);
}
