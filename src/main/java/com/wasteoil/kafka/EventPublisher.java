package com.wasteoil.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 领域事件发布器。业务事务提交后调用，事件写入 Kafka 供审计/分析消费。
 */
@Slf4j
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String topic, String key, String type, Map<String, Object> payload) {
        DomainEvent event = DomainEvent.of(type, payload);
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    // 事件发送失败不阻断主流程，记录日志由运维补偿
                    log.error("事件发送失败 topic={} key={} type={}", topic, key, type, ex);
                }
            });
        } catch (JsonProcessingException e) {
            log.error("事件序列化失败 topic={} type={}", topic, type, e);
        }
    }
}
