package com.wasteoil.weighing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 到店扫码核验记录：核验门店、交接人、现场照片。
 */
@Getter
@Setter
@Entity
@Table(name = "pickup_verifications")
public class PickupVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispatch_id", nullable = false)
    private Long dispatchId;

    @Column(name = "store_qr", nullable = false)
    private String storeQr;

    @Column(name = "device_code")
    private String deviceCode;

    /** 交接人 */
    @Column(name = "handover_name", nullable = false)
    private String handoverName;

    /** 现场照片（逗号分隔的 URL） */
    @Column(name = "photo_urls")
    private String photoUrls;

    @Column(name = "verified_by", nullable = false)
    private String verifiedBy;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt = Instant.now();
}
