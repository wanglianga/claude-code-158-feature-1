package com.wasteoil.weighing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 称重设备。设备离线时其数据可信度降级，需人工补录并标记。
 */
@Getter
@Setter
@Entity
@Table(name = "weighing_devices")
public class WeighingDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** ONLINE / OFFLINE */
    @Column(nullable = false)
    private String status = "ONLINE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
