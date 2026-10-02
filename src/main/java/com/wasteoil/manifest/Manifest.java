package com.wasteoil.manifest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电子联单：从派单到结算的全链路单一口径。
 * 司机、商户、处置厂、监管端读取的都是同一份数据。
 */
@Getter
@Setter
@Entity
@Table(name = "manifests")
public class Manifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 联单编号，如 M20260926-0001 */
    @Column(name = "manifest_no", nullable = false, unique = true)
    private String manifestNo;

    @Column(name = "dispatch_id", nullable = false, unique = true)
    private Long dispatchId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "plant_id", nullable = false)
    private Long plantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ManifestStatus status = ManifestStatus.CREATED;

    /** 出店装车桶数 */
    @Column(name = "barrel_count_exit", nullable = false)
    private Integer barrelCountExit = 0;

    /** 到厂桶数 */
    @Column(name = "barrel_count_arrival")
    private Integer barrelCountArrival;

    /** 出店重量（门店称重合计，公斤） */
    @Column(name = "exit_weight_kg", nullable = false)
    private BigDecimal exitWeightKg = BigDecimal.ZERO;

    /** 到厂重量（处置厂二次称重，公斤） */
    @Column(name = "arrival_weight_kg")
    private BigDecimal arrivalWeightKg;

    /** 运输损耗（公斤） */
    @Column(name = "loss_kg")
    private BigDecimal lossKg;

    /** 损耗率 */
    @Column(name = "loss_rate")
    private BigDecimal lossRate;

    /** 手动补录称重条数 */
    @Column(name = "manual_weigh_count", nullable = false)
    private Integer manualWeighCount = 0;

    /** 设备离线状态下称重条数 */
    @Column(name = "offline_weigh_count", nullable = false)
    private Integer offlineWeighCount = 0;

    /** 离线补录复核状态：NONE / PENDING / APPROVED / REJECTED */
    @Column(name = "supplement_review_status", nullable = false)
    private String supplementReviewStatus = "NONE";

    /** 人工补录标记：复核通过后打上，监管抽查可优先查看 */
    @Column(name = "manual_supplement_flag", nullable = false)
    private Boolean manualSupplementFlag = false;

    /** 运输轨迹是否缺失 */
    @Column(name = "track_missing", nullable = false)
    private Boolean trackMissing = false;

    /** 是否存在质量问题（混入厨余） */
    @Column(name = "quality_issue", nullable = false)
    private Boolean qualityIssue = false;

    /** 合规比对结果：PASS / EXCEPTION */
    @Column(name = "compliance_result")
    private String complianceResult;

    @Column(name = "exception_note")
    private String exceptionNote;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
