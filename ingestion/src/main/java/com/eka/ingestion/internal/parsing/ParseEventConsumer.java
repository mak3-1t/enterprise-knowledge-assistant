package com.eka.ingestion.internal.parsing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eka.ingestion.parser.enabled", havingValue = "true")
public class ParseEventConsumer {
    private final ParseJobRepository jobs;

    public ParseEventConsumer(ParseJobRepository jobs) {
        this.jobs = jobs;
    }

    @KafkaListener(
            topics = "${eka.ingestion.kafka.topic:eka.documents.events.v1}",
            groupId = "${eka.ingestion.parser.group-id:eka-document-parser-v1}",
            containerFactory = "documentParseListenerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        try {
            jobs.accept(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.value());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("PARSE_INTAKE_UNAVAILABLE");
        }
    }
}
