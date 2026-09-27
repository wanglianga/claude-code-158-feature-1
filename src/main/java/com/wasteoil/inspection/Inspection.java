package com.wasteoil.inspection;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 监管抽查记录。
 */
@Getter
@Setter
@Entity
@Table(name = "inspections")
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false)
    private Long manifestId;

    @Column(nullable = false)
    private String inspector;

    /** PASS / FAIL */
    @Column(nullable = false)
    private String result;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
