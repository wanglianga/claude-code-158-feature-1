package com.wasteoil.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByManifestId(Long manifestId);

    List<Settlement> findByStoreIdOrderByCreatedAtDesc(Long storeId);
}
