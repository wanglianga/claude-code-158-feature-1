package com.wasteoil.weighing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WeighingRecordRepository extends JpaRepository<WeighingRecord, Long> {

    List<WeighingRecord> findByDispatchId(Long dispatchId);

    List<WeighingRecord> findByManifestId(Long manifestId);

    List<WeighingRecord> findByBarrelIdOrderByWeighedAtDesc(Long barrelId);

    boolean existsByDispatchIdAndBarrelId(Long dispatchId, Long barrelId);

    /** 某司机在指定时间之后的离线补录条数（频繁离线判定） */
    @Query("SELECT COUNT(w) FROM WeighingRecord w, DispatchOrder d "
            + "WHERE w.dispatchId = d.id AND d.driverId = :driverId "
            + "AND w.source = 'OFFLINE_MANUAL' AND w.weighedAt >= :since")
    long countOfflineSupplementsByDriverSince(@Param("driverId") Long driverId,
                                              @Param("since") Instant since);

    /** 某司机全部人工录入条数（MANUAL + OFFLINE_MANUAL，复核时参考补录频次） */
    @Query("SELECT COUNT(w) FROM WeighingRecord w, DispatchOrder d "
            + "WHERE w.dispatchId = d.id AND d.driverId = :driverId "
            + "AND w.source IN ('MANUAL', 'OFFLINE_MANUAL')")
    long countManualEntriesByDriver(@Param("driverId") Long driverId);
}
