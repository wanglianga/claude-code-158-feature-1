package com.wasteoil.weighing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeighingDeviceRepository extends JpaRepository<WeighingDevice, Long> {

    Optional<WeighingDevice> findByCode(String code);

    List<WeighingDevice> findByStoreId(Long storeId);
}
