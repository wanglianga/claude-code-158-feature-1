package com.wasteoil.supplement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线补录单：司机到店蓝牙秤无法连接时，拍摄秤面照片并手动录入重量。
 * 补录批次必须经人工复核（照片清晰度、油桶数量、历史重量、补录频次）后方可采信。
 */
@Getter
@Setter
@Entity
@Table(name = "offline_supplements")
public class OfflineSupplement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 补录单编号，如 S20261002-0001 */
    @Column(name = "supplement_no", nullable = false, unique = true)
    private String supplementNo;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** 司机手录申报总重（公斤） */
    @Column(name = "declared_weight_kg", nullable = false)
    private BigDecimal declaredWeightKg;

    /** 申报油桶数量 */
    @Column(name = "declared_barrel_count", nullable = false)
    private Integer declaredBarrelCount;

    /** 申报桶码（逗号分隔，驳回删除称重明细后仍可凭此留痕） */
    @Column(name = "barrel_codes", nullable = false)
    private String barrelCodes;

    /** 秤面照片（多张以逗号分隔） */
    @Column(name = "photo_urls", nullable = false)
    private String photoUrls;

    /** 设备离线情况说明 */
    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplementStatus status = SupplementStatus.PENDING_REVIEW;

    /** 复核员判定照片是否清晰可辨 */
    @Column(name = "photo_clear")
    private Boolean photoClear;

    /** 复核员判定油桶数量是否与申报相符 */
    @Column(name = "barrel_count_match")
    private Boolean barrelCountMatch;

    /** 复核人账号 */
    @Column(name = "reviewer")
    private String reviewer;

    @Column(name = "review_comment")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
