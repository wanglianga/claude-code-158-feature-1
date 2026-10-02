package com.wasteoil.dispatch;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String phone;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    /** 司机信用分（异常补录被驳回会扣分） */
    @Column(name = "credit_score", nullable = false)
    private Integer creditScore = 100;

    /** 是否被限制手动补录（频繁设备离线时回收公司可限制） */
    @Column(name = "manual_restricted", nullable = false)
    private Boolean manualRestricted = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
