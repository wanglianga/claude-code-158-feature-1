package com.wasteoil.weighing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PickupVerificationRepository extends JpaRepository<PickupVerification, Long> {

    List<PickupVerification> findByDispatchId(Long dispatchId);
}
