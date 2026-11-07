package com.eka.ingestion.internal.parsing;

import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(name = "eka.ingestion.parser.enabled", havingValue = "true")
public class ParserConfiguration {
    public ParserConfiguration(
            @Value("${eka.ingestion.kafka.enabled:false}") boolean kafka,
            @Value("${eka.upload.enabled:false}") boolean storage) {
        if (!kafka || !storage) {
            throw new IllegalStateException(
                    "Parser requires Kafka relay and private upload storage configuration");
        }
    }

    @Bean
    TextExtractor documentTextExtractor() {
        return new ProcessTextExtractor();
    }

    @Bean
    ThreadPoolTaskScheduler parseWorkerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("document-parse-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> documentParseListenerFactory(
            KafkaProperties properties, Optional<ParseDeadLetterPublisher> deadLetters) {
        var config = new java.util.HashMap<String, Object>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 16384);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var handler =
                deadLetters
                        .map(
                                publisher ->
                                        new DefaultErrorHandler(
                                                publisher::publish, new FixedBackOff(1000, 3)))
                        .orElseGet(
                                () ->
                                        new DefaultErrorHandler(
                                                new FixedBackOff(
                                                        1000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        handler.addNotRetryableExceptions(ParseRejected.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
