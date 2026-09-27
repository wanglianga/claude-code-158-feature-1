package com.wasteoil.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RiskEntryRepository extends JpaRepository<RiskEntry, Long> {

    List<RiskEntry> findByStoreId(Long storeId);

    List<RiskEntry> findByStatus(String status);

    Optional<RiskEntry> findByStoreIdAndIndicatorAndWindowEndAndStatus(
            Long storeId, String indicator, LocalDate windowEnd, String status);
}
