package com.wasteoil.dispute;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByManifestId(Long manifestId);

    List<Dispute> findByStatus(String status);
}
