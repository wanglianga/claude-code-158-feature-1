package com.wasteoil.dispatch;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "dispatch_orders")
public class DispatchOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false, unique = true)
    private Long appointmentId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "plant_id", nullable = false)
    private Long plantId;

    /** 计划到店时间窗 */
    @Column(name = "planned_start", nullable = false)
    private Instant plannedStart;

    @Column(name = "planned_end", nullable = false)
    private Instant plannedEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DispatchStatus status = DispatchStatus.PLANNED;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "load_done_at")
    private Instant loadDoneAt;

    @Column(name = "plant_arrived_at")
    private Instant plantArrivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "route_note")
    private String routeNote;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
