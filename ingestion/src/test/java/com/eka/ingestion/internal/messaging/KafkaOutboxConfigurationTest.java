package com.eka.ingestion.internal.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaOutboxConfigurationTest {
    @Test
    void bootAutoConfigurationUsesOneExplicitBoundedIdempotentProducerWhenEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(KafkaOutboxConfiguration.class)
                .withPropertyValues(
                        "eka.ingestion.kafka.enabled=true",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(KafkaTemplate.class)
                                    .hasSingleBean(DefaultKafkaProducerFactory.class);
                            var config =
                                    context.getBean(DefaultKafkaProducerFactory.class)
                                            .getConfigurationProperties();
                            assertThat(config)
                                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                                    .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                                    .containsEntry(
                                            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
                        });
    }

    @Test
    void customRelaySchedulersAndTopicCreationRemainOffByDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(KafkaOutboxConfiguration.class)
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean("outboxRelayScheduler")
                                        .doesNotHaveBean("documentEventsTopic"));
    }
}
