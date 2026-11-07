package com.eka.ingestion.internal.parsing;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

final class ParseDeadLetterPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    ParseDeadLetterPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json, String topic) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    void publish(ConsumerRecord<?, ?> record, Exception failure) {
        var reason = reason(failure);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schemaVersion", 1);
        metadata.put("topic", record.topic());
        metadata.put("partition", record.partition());
        metadata.put("offset", record.offset());
        metadata.put("reason", reason.name());
        if (reason == ParseIntakeOutcome.INTAKE_UNAVAILABLE
                && record.key() instanceof String key
                && record.value() instanceof String value) {
            var event = ParseEvent.decode(key, value);
            if (event != null && event.type().equals("CONTENT_VERIFIED")) {
                metadata.put("eventIdHint", event.id().toString());
            }
        }
        String identity = record.topic() + "/" + record.partition() + "/" + record.offset();
        try {
            kafka.send(topic, identity, json.writeValueAsString(metadata))
                    .get(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PARSE_DEAD_LETTER_UNAVAILABLE");
        } catch (Exception unavailable) {
            throw new IllegalStateException("PARSE_DEAD_LETTER_UNAVAILABLE");
        }
    }

    private static ParseIntakeOutcome reason(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++) {
            if (failure instanceof ParseRejected rejected) {
                return rejected.reason();
            }
            failure = failure.getCause();
        }
        return ParseIntakeOutcome.INTAKE_UNAVAILABLE;
    }
}
