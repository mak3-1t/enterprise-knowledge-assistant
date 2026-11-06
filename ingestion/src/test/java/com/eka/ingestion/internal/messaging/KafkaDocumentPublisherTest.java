package com.eka.ingestion.internal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

class KafkaDocumentPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final JsonMapper json = JsonMapper.builder().build();
    private final KafkaDocumentPublisher publisher =
            new KafkaDocumentPublisher(kafka, json, "eka.documents.events.v1");

    @Test
    void envelopeIsStableAcrossLeasesAndContainsOnlyVersionedPublicEventFields() {
        var first = OutboxRelayTest.delivery();
        var second =
                new OutboxDelivery(
                        first.id(),
                        first.tenantId(),
                        first.documentId(),
                        first.documentVersion(),
                        first.eventType(),
                        first.occurredAt(),
                        1,
                        java.util.UUID.randomUUID(),
                        2);
        assertThat(publisher.encode(first)).isEqualTo(publisher.encode(second));
        var event = json.readTree(publisher.encode(first));
        assertThat(event.size()).isEqualTo(7);
        assertThat(event.get("eventId").asText()).isEqualTo(first.id().toString());
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.get("tenantId").asText()).isEqualTo(first.tenantId().toString());
        assertThat(publisher.encode(first))
                .doesNotContain("lease", "attempt", "filename", "objectKey", "password");
        assertThat(first.partitionKey()).isEqualTo(first.tenantId() + "/" + first.documentId());
    }

    @Test
    void brokerReceiptUsesActualPartitionAndOffset() {
        var event = OutboxRelayTest.delivery();
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(
                        call -> {
                            ProducerRecord<String, String> record = call.getArgument(0);
                            assertThat(record.key()).isEqualTo(event.partitionKey());
                            assertThat(record.topic()).isEqualTo("eka.documents.events.v1");
                            return CompletableFuture.completedFuture(
                                    new SendResult<>(
                                            record,
                                            new RecordMetadata(
                                                    new TopicPartition(record.topic(), 2),
                                                    100,
                                                    0,
                                                    0,
                                                    0,
                                                    0)));
                        });
        assertThat(publisher.publish(event)).isEqualTo(new OutboxDelivery.Receipt(2, 100));
    }

    @Test
    void failureNeverReturnsAnAcknowledgementOrLeaksBrokerDiagnostic() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("private broker diagnostic")));
        assertThatThrownBy(() -> publisher.publish(OutboxRelayTest.delivery()))
                .isInstanceOf(PublicationUnavailable.class)
                .hasMessage("BROKER_UNAVAILABLE")
                .hasNoCause();
    }

    @Test
    void invalidTopicAndReceiptAreRejectedBeforeUse() {
        assertThatThrownBy(() -> new KafkaDocumentPublisher(kafka, json, "bad topic"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxDelivery.Receipt(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxDelivery.Receipt(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
