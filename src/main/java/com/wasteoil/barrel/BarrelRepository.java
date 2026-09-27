package com.wasteoil.barrel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BarrelRepository extends JpaRepository<Barrel, Long> {

    Optional<Barrel> findByCode(String code);

    List<Barrel> findByStoreId(Long storeId);
}
