package com.wasteoil.risk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 风险名单条目：长期产量/营业规模/回收频次不匹配的商户门店。
 */
@Getter
@Setter
@Entity
@Table(name = "risk_entries")
public class RiskEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** YIELD_MISMATCH / NO_RECYCLING / MANUAL_WEIGH_HEAVY / INSPECTION_FAIL */
    @Column(nullable = false)
    private String indicator;

    @Column(nullable = false)
    private String detail;

    /** 风险分（0-100） */
    @Column(nullable = false)
    private Integer score;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    /** OPEN / CONFIRMED / DISMISSED */
    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
