package com.wasteoil.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByMerchantId(Long merchantId);

    Optional<Store> findByQrCode(String qrCode);
}
