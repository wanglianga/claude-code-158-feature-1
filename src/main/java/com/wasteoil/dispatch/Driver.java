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

    /** 司机信用分（初始 100），离线补录被复核驳回等异常行为扣减 */
    @Column(name = "credit_score", nullable = false)
    private Integer creditScore = 100;

    /** 是否被限制继续手动补录（频繁设备离线/信用过低） */
    @Column(name = "manual_blocked", nullable = false)
    private Boolean manualBlocked = false;

    /** 回收公司最近一次完成设备核查的时间，补录频次自此重新起算 */
    @Column(name = "last_device_check_at")
    private Instant lastDeviceCheckAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
