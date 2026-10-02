package com.wasteoil.supplement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 离线补录复核单。司机在蓝牙秤无法连接时拍摄秤面照片手动补录后，
 * 该批联单生成复核单进入待复核；复核人员核对照片清晰度、油桶数量、
 * 历史重量与司机补录频次后决定放行结算或驳回（影响司机信用）。
 */
@Getter
@Setter
@Entity
@Table(name = "supplement_reviews")
public class SupplementReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一张联单对应一条复核单（该批联单统一复核） */
    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    /** PENDING / APPROVED / REJECTED */
    @Column(nullable = false)
    private String status = "PENDING";

    /** 本批离线补录条数 */
    @Column(name = "supplement_count", nullable = false)
    private Integer supplementCount = 0;

    /** 复核登记的照片清晰度：CLEAR / BLURRY */
    @Column(name = "photo_clarity")
    private String photoClarity;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "reviewer")
    private String reviewer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}
