package com.wasteoil.dispute;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商户重量质疑。质疑期间联单进入 DISPUTED，结算冻结。
 */
@Getter
@Setter
@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private String reason;

    /** OPEN / RESOLVED */
    @Column(nullable = false)
    private String status = "OPEN";

    private String resolution;

    /** 协商后的结算重量（为空表示维持原重量） */
    @Column(name = "adjusted_weight_kg")
    private BigDecimal adjustedWeightKg;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
