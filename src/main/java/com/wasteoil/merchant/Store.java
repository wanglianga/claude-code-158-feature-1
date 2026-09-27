package com.wasteoil.merchant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

@Getter
@Setter
@Entity
@Table(name = "stores")
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    /** 所属商圈，用于货车限行规则匹配 */
    @Column(nullable = false)
    private String district;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "contact_phone", nullable = false)
    private String contactPhone;

    /** 废油存放位置 */
    @Column(name = "storage_location", nullable = false)
    private String storageLocation;

    /** 营业规模档位：SMALL / MEDIUM / LARGE */
    @Column(name = "scale_tier", nullable = false)
    private String scaleTier;

    /** 按营业规模预估的日均废油量（公斤），风险分析基准 */
    @Column(name = "expected_daily_oil_kg", nullable = false)
    private BigDecimal expectedDailyOilKg;

    /** 门店核验二维码内容 */
    @Column(name = "qr_code", nullable = false, unique = true)
    private String qrCode;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
