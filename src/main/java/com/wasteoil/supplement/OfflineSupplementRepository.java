package com.wasteoil.supplement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OfflineSupplementRepository extends JpaRepository<OfflineSupplement, Long> {

    List<OfflineSupplement> findByManifestId(Long manifestId);

    List<OfflineSupplement> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    List<OfflineSupplement> findByStatusOrderByCreatedAtAsc(SupplementStatus status);

    /** 某司机指定时间之后的补录单（统计补录频次） */
    List<OfflineSupplement> findByDriverIdAndCreatedAtAfter(Long driverId, Instant after);

    long countByDriverIdAndCreatedAtAfter(Long driverId, Instant after);

    long countByDriverIdAndStatusAndCreatedAtAfter(Long driverId, SupplementStatus status, Instant after);

    long countByDeviceIdAndCreatedAtAfter(Long deviceId, Instant after);

    boolean existsByDispatchIdAndStatus(Long dispatchId, SupplementStatus status);
}
