package com.eka.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.listener.ContainerProperties;
import tools.jackson.databind.json.JsonMapper;

class ParseIntakeTest {
    @Test
    void validatesCanonicalIdentityKeySchemaAndTimestampWithoutEchoingPayload() {
        UUID tenant = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        String key = tenant + "/" + document;
        String payload = payload(UUID.randomUUID(), tenant, document, 3, Instant.now());
        assertThat(ParseEvent.decode(key, payload)).isNotNull();
        assertThat(ParseEvent.decode("foreign", payload)).isNull();
        assertThat(
                        ParseEvent.decode(
                                key,
                                payload.replace(
                                        "\"schemaVersion\":1", "\"schemaVersion\":4294967297")))
                .isNull();
        assertThat(
                        ParseEvent.decode(
                                key,
                                payload.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5")))
                .isNull();
        assertThat(ParseEvent.decode(key, "{" + "\"schemaVersion\":1," + payload.substring(1)))
                .isNull();
        assertThat(ParseEvent.decode(key, "not JSON private text")).isNull();
    }

    @Test
    void consumerReturnsOnlyAfterInboxCallAndStripsDatabaseDiagnosticsOnFailure() {
        var jobs = mock(ParseJobRepository.class);
        var consumer = new ParseEventConsumer(jobs);
        var record = new ConsumerRecord<>("events", 0, 1L, "key", "payload");
        consumer.receive(record);
        verify(jobs).accept("events", 0, 1L, "key", "payload");
        doThrow(new IllegalStateException("private database details"))
                .when(jobs)
                .accept(any(), anyInt(), anyLong(), any(), any());
        assertThatThrownBy(() -> consumer.receive(record))
                .hasMessage("PARSE_INTAKE_UNAVAILABLE")
                .hasNoCause();
    }

    @Test
    void parserRequiresConfiguredStorageKafkaAndUsesRecordAcknowledgement() {
        assertThatThrownBy(() -> new ParserConfiguration(false, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ParserConfiguration(true, false))
                .isInstanceOf(IllegalStateException.class);
        var factory =
                new ParserConfiguration(true, true)
                        .documentParseListenerFactory(new KafkaProperties());
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    static String payload(
            UUID event, UUID tenant, UUID document, long version, Instant occurredAt) {
        return JsonMapper.builder()
                .build()
                .writeValueAsString(
                        Map.of(
                                "schemaVersion",
                                1,
                                "eventId",
                                event.toString(),
                                "tenantId",
                                tenant.toString(),
                                "documentId",
                                document.toString(),
                                "documentVersion",
                                version,
                                "eventType",
                                "CONTENT_VERIFIED",
                                "occurredAt",
                                occurredAt.toString()));
    }
}
