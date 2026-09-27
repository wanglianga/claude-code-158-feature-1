package com.wasteoil.appointment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    List<Appointment> findByStatus(AppointmentStatus status);
}
