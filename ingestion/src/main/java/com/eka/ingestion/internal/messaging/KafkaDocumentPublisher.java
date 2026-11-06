package com.eka.ingestion.internal.messaging;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "eka.ingestion.kafka.enabled", havingValue = "true")
public class KafkaDocumentPublisher implements OutboxPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public KafkaDocumentPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper json,
            @Value("${eka.ingestion.kafka.topic:eka.documents.events.v1}") String topic) {
        validateTopic(topic);
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    @Override
    public OutboxDelivery.Receipt publish(OutboxDelivery delivery) {
        try {
            var result =
                    kafka.send(
                                    new ProducerRecord<>(
                                            topic, delivery.partitionKey(), encode(delivery)))
                            .get(20, TimeUnit.SECONDS)
                            .getRecordMetadata();
            return new OutboxDelivery.Receipt(result.partition(), result.offset());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PublicationUnavailable();
        } catch (Exception unavailable) {
            // An error/timeout can follow a successful broker append: redelivery uses the same
            // event ID.
            throw new PublicationUnavailable();
        }
    }

    String encode(OutboxDelivery delivery) {
        return json.writeValueAsString(
                Map.of(
                        "schemaVersion",
                        delivery.schemaVersion(),
                        "eventId",
                        delivery.id().toString(),
                        "tenantId",
                        delivery.tenantId().toString(),
                        "documentId",
                        delivery.documentId().toString(),
                        "documentVersion",
                        delivery.documentVersion(),
                        "eventType",
                        delivery.eventType(),
                        "occurredAt",
                        delivery.occurredAt().toString()));
    }

    static void validateTopic(String topic) {
        if (topic == null
                || !topic.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,248}")
                || topic.equals(".")
                || topic.equals("..")) {
            throw new IllegalArgumentException("Invalid document event topic");
        }
    }
}
