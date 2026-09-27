package com.wasteoil.kafka;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 领域事件统一信封。payload 为事件特有的键值对。
 */
public record DomainEvent(
        String eventId,
        String type,
        Instant occurredAt,
        Map<String, Object> payload) {

    public static DomainEvent of(String type, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID().toString(), type, Instant.now(), payload);
    }
}
