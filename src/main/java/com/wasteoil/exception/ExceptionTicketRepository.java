package com.wasteoil.exception;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExceptionTicketRepository extends JpaRepository<ExceptionTicket, Long> {

    List<ExceptionTicket> findByRefTypeAndRefId(String refType, Long refId);

    List<ExceptionTicket> findByStatus(String status);

    boolean existsByTypeAndRefTypeAndRefIdAndStatus(ExceptionType type, String refType, Long refId, String status);

    List<ExceptionTicket> findByRefTypeAndRefIdAndBlockingAndStatus(String refType, Long refId, Boolean blocking, String status);
}
