package com.wasteoil.dispatch;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

/**
 * 商圈货车限行规则。
 */
@Getter
@Setter
@Entity
@Table(name = "district_restrictions")
public class DistrictRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String district;

    @Column(name = "restrict_start", nullable = false)
    private LocalTime restrictStart;

    @Column(name = "restrict_end", nullable = false)
    private LocalTime restrictEnd;

    private String note;
}
