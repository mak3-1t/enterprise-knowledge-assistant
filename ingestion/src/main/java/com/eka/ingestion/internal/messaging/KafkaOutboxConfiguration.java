package com.eka.ingestion.internal.messaging;

import java.util.HashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "eka.ingestion.kafka.enabled", havingValue = "true")
public class KafkaOutboxConfiguration {
    @Bean
    DefaultKafkaProducerFactory<String, String> outboxProducerFactory(KafkaProperties properties) {
        var config = new HashMap<String, Object>(properties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 16384);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> outboxKafkaTemplate(
            DefaultKafkaProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }

    @Bean
    ThreadPoolTaskScheduler outboxRelayScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("outbox-relay-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    ThreadPoolTaskScheduler uploadTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("document-work-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "eka.ingestion.kafka.create-topic", havingValue = "true")
    NewTopic documentEventsTopic(
            @Value("${eka.ingestion.kafka.topic:eka.documents.events.v1}") String topic) {
        KafkaDocumentPublisher.validateTopic(topic);
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
