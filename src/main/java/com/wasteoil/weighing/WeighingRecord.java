package com.wasteoil.weighing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 称重记录。设备直传或司机手动补录；离线设备数据与手动补录均会被标记，
 * 在联单上体现为可信度降级的数据来源。
 */
@Getter
@Setter
@Entity
@Table(name = "weighing_records")
public class WeighingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(name = "barrel_id", nullable = false)
    private Long barrelId;

    @Column(name = "device_id")
    private Long deviceId;

    /** 称重重量（公斤） */
    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    /** DEVICE / MANUAL */
    @Column(nullable = false)
    private String source;

    /** 称重时设备是否处于离线状态 */
    @Column(name = "device_offline", nullable = false)
    private Boolean deviceOffline = false;

    /** 现场照片 */
    @Column(name = "photo_url")
    private String photoUrl;

    /** 交接人 */
    @Column(name = "handover_name")
    private String handoverName;

    @Column(name = "note")
    private String note;

    @Column(name = "weighed_at", nullable = false)
    private Instant weighedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
