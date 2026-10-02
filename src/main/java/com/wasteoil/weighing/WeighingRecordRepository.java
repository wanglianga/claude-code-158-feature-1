package com.wasteoil.weighing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WeighingRecordRepository extends JpaRepository<WeighingRecord, Long> {

    List<WeighingRecord> findByDispatchId(Long dispatchId);

    List<WeighingRecord> findByManifestId(Long manifestId);

    List<WeighingRecord> findByBarrelIdOrderByWeighedAtDesc(Long barrelId);

    boolean existsByDispatchIdAndBarrelId(Long dispatchId, Long barrelId);

    List<WeighingRecord> findBySupplementId(Long supplementId);
}
