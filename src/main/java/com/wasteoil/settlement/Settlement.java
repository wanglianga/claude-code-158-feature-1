package com.wasteoil.settlement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算单。联单 VERIFIED 后按到厂重量生成；商户质疑时冻结，争议解决后调整。
 */
@Getter
@Setter
@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** 结算重量（默认到厂重量，争议解决后可调整） */
    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private BigDecimal amount;

    /** PENDING / FROZEN / CONFIRMED */
    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
