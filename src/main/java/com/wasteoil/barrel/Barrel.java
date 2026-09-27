package com.wasteoil.barrel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "barrels")
public class Barrel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 桶身二维码，一桶一码 */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "capacity_kg", nullable = false)
    private BigDecimal capacityKg;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BarrelStatus status = BarrelStatus.AT_STORE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
