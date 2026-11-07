package com.eka.ingestion.internal.parsing;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"eka.ingestion.parser.enabled", "eka.ingestion.parser.dead-letter-enabled"},
        havingValue = "true")
class ParseDeadLetterConfiguration {
    private final String topic;

    ParseDeadLetterConfiguration(
            @Value("${eka.ingestion.kafka.topic:eka.documents.events.v1}") String source,
            @Value("${eka.ingestion.parser.dead-letter-topic:eka.documents.parser.dlt.v1}")
                    String topic) {
        if (source.equals(topic) || !topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}")) {
            throw new IllegalArgumentException("Invalid parser dead-letter topic");
        }
        this.topic = topic;
    }

    @Bean
    ParseDeadLetterPublisher parseDeadLetterPublisher(
            KafkaTemplate<String, String> kafka, ObjectMapper json) {
        return new ParseDeadLetterPublisher(kafka, json, topic);
    }

    @Bean
    @ConditionalOnProperty(name = "eka.ingestion.kafka.create-topic", havingValue = "true")
    NewTopic parserDeadLetterTopic() {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
