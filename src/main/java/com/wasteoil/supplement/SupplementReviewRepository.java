package com.wasteoil.supplement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplementReviewRepository extends JpaRepository<SupplementReview, Long> {

    Optional<SupplementReview> findByManifestId(Long manifestId);

    List<SupplementReview> findByStatusOrderByCreatedAtAsc(String status);

    List<SupplementReview> findAllByOrderByCreatedAtDesc();
}
