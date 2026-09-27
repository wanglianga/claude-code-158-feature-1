package com.wasteoil.exception;

import com.wasteoil.common.AuthContext;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常工单服务：统一登记、查询、处理各类异常。
 */
@Slf4j
@Service
public class ExceptionService {

    private final ExceptionTicketRepository ticketRepository;
    private final EventPublisher eventPublisher;

    public ExceptionService(ExceptionTicketRepository ticketRepository, EventPublisher eventPublisher) {
        this.ticketRepository = ticketRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 登记异常（同一对象同一类型的未处理异常不重复登记）。
     */
    @Transactional
    public ExceptionTicket raise(ExceptionType type, String refType, Long refId, String description) {
        if (ticketRepository.existsByTypeAndRefTypeAndRefIdAndStatus(type, refType, refId, "OPEN")) {
            log.info("异常已存在，跳过重复登记: type={} ref={}/{}", type, refType, refId);
            return ticketRepository.findByRefTypeAndRefId(refType, refId).stream()
                    .filter(t -> t.getType() == type && "OPEN".equals(t.getStatus()))
                    .findFirst().orElseThrow();
        }
        ExceptionTicket ticket = new ExceptionTicket();
        ticket.setType(type);
        ticket.setRefType(refType);
        ticket.setRefId(refId);
        ticket.setDescription(description);
        ticket.setBlocking(type.isBlocking());
        ticket.setStatus("OPEN");
        ExceptionTicket saved = ticketRepository.save(ticket);

        Map<String, Object> payload = new HashMap<>();
        payload.put("ticketId", saved.getId());
        payload.put("type", type.name());
        payload.put("refType", refType);
        payload.put("refId", refId);
        payload.put("description", description);
        payload.put("blocking", saved.getBlocking());
        eventPublisher.publish(KafkaTopics.EXCEPTION_EVENTS, refType + "-" + refId, "ExceptionRaised", payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ExceptionTicket> list(String status) {
        if (status == null || status.isBlank()) {
            return ticketRepository.findAll();
        }
        return ticketRepository.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public List<ExceptionTicket> listByRef(String refType, Long refId) {
        return ticketRepository.findByRefTypeAndRefId(refType, refId);
    }

    /**
     * 处理异常工单。
     *
     * @return 处理后的工单
     */
    @Transactional
    public ExceptionTicket resolve(Long ticketId, String resolution) {
        ExceptionTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> com.wasteoil.common.BizException.notFound("异常工单"));
        if (!"OPEN".equals(ticket.getStatus())) {
            throw com.wasteoil.common.BizException.conflict("工单已处理，不能重复处理");
        }
        ticket.setStatus("RESOLVED");
        ticket.setResolution(resolution);
        ticket.setHandler(AuthContext.accountId() == null ? "system" : AuthContext.accountId());
        ticket.setHandledAt(Instant.now());
        ExceptionTicket saved = ticketRepository.save(ticket);

        Map<String, Object> payload = new HashMap<>();
        payload.put("ticketId", saved.getId());
        payload.put("type", saved.getType().name());
        payload.put("refType", saved.getRefType());
        payload.put("refId", saved.getRefId());
        payload.put("resolution", resolution);
        eventPublisher.publish(KafkaTopics.EXCEPTION_EVENTS,
                saved.getRefType() + "-" + saved.getRefId(), "ExceptionResolved", payload);
        return saved;
    }

    /**
     * 某联单是否还有未处理的阻断性异常。
     */
    @Transactional(readOnly = true)
    public boolean hasBlockingOpen(String refType, Long refId) {
        return !ticketRepository
                .findByRefTypeAndRefIdAndBlockingAndStatus(refType, refId, true, "OPEN")
                .isEmpty();
    }
}
