package com.wasteoil.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DispatchOrderRepository extends JpaRepository<DispatchOrder, Long> {

    Optional<DispatchOrder> findByAppointmentId(Long appointmentId);

    List<DispatchOrder> findByStatus(DispatchStatus status);

    List<DispatchOrder> findByStatusIn(List<DispatchStatus> statuses);

    List<DispatchOrder> findByVehicleId(Long vehicleId);

    List<DispatchOrder> findByDriverId(Long driverId);
}
