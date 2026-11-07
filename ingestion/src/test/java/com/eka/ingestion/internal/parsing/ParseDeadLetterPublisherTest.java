package com.eka.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

class ParseDeadLetterPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void publishesOnlySafeMetadataWithStableKey() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var publisher =
                new ParseDeadLetterPublisher(kafka, JsonMapper.builder().build(), "parser.dlt");
        var record = new ConsumerRecord<>("events", 2, 9, "private-key", "private-body");
        publisher.publish(
                record,
                new IllegalStateException(
                        "private-exception", new ParseRejected(ParseIntakeOutcome.INVALID_EVENT)));

        var body = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("parser.dlt"), eq("events/2/9"), body.capture());
        assertThat(body.getValue())
                .contains("INVALID_EVENT", "\"offset\":9")
                .doesNotContain("private-key", "private-body", "private-exception");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedBrokerWriteDoesNotReportRecovery() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private")));
        var publisher =
                new ParseDeadLetterPublisher(kafka, JsonMapper.builder().build(), "parser.dlt");
        var record = new ConsumerRecord<>("events", 2, 9, "key", "body");
        assertThatThrownBy(
                        () -> publisher.publish(record, new IllegalStateException("unavailable")))
                .hasMessage("PARSE_DEAD_LETTER_UNAVAILABLE")
                .hasNoCause();
    }
}
