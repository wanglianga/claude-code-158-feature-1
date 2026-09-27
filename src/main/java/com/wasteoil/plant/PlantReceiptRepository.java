package com.wasteoil.plant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlantReceiptRepository extends JpaRepository<PlantReceipt, Long> {

    Optional<PlantReceipt> findByManifestId(Long manifestId);
}
