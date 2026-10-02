package com.wasteoil.manifest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ManifestRepository extends JpaRepository<Manifest, Long> {

    Optional<Manifest> findByManifestNo(String manifestNo);

    Optional<Manifest> findByDispatchId(Long dispatchId);

    List<Manifest> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    List<Manifest> findByStatus(ManifestStatus status);

    List<Manifest> findByStoreIdAndCreatedAtBetween(Long storeId, Instant from, Instant to);

    List<Manifest> findByCreatedAtBetween(Instant from, Instant to);

    /** 带人工补录标记的联单（监管抽查优先查看） */
    List<Manifest> findByManualSupplementFlagTrueOrderByCreatedAtDesc();
}
