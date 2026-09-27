package com.wasteoil.plant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

@Getter
@Setter
@Entity
@Table(name = "plants")
public class Plant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    /** 接收窗口开始 */
    @Column(name = "receiving_open", nullable = false)
    private LocalTime receivingOpen;

    /** 接收窗口结束 */
    @Column(name = "receiving_close", nullable = false)
    private LocalTime receivingClose;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
