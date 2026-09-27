package com.wasteoil.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DistrictRestrictionRepository extends JpaRepository<DistrictRestriction, Long> {

    List<DistrictRestriction> findByDistrict(String district);
}
