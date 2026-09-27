package com.wasteoil.exception;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 异常工单。blocking=true 的异常会阻断联单闭环，处理完毕前联单保持 EXCEPTION 状态。
 */
@Getter
@Setter
@Entity
@Table(name = "exception_tickets")
public class ExceptionTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExceptionType type;

    /** 关联对象类型：APPOINTMENT / DISPATCH / MANIFEST */
    @Column(name = "ref_type", nullable = false)
    private String refType;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @Column(nullable = false)
    private String description;

    /** 是否阻断联单闭环 */
    @Column(nullable = false)
    private Boolean blocking = false;

    /** OPEN / RESOLVED */
    @Column(nullable = false)
    private String status = "OPEN";

    private String handler;

    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "handled_at")
    private Instant handledAt;
}
