package com.eka.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eka.ingestion.internal.messaging.KafkaDocumentPublisher;
import com.eka.ingestion.internal.messaging.OutboxDelivery;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.service.UploadEvents;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class KafkaParseIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    private static ParseDatabase db;
    private static DefaultKafkaProducerFactory<String, String> producer;
    private static KafkaTemplate<String, String> template;
    private String topic;

    @BeforeAll
    static void setup() {
        db = new ParseDatabase(POSTGRES);
        producer =
                new DefaultKafkaProducerFactory<>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers(),
                                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class,
                                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class,
                                ProducerConfig.ACKS_CONFIG,
                                "all"));
        template = new KafkaTemplate<>(producer);
    }

    @AfterAll
    static void close() {
        if (template != null) {
            template.destroy();
        }
        if (producer != null) {
            producer.destroy();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() throws Exception {
        db.reset();
        topic = "eka-parse-" + UUID.randomUUID();
        try (var admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void brokerDeliverySurvivesRedeliveryAndFeedsRealChildParserWithoutReady() throws Exception {
        var event = db.verified(UUID.randomUUID(), UUID.randomUUID());
        String group = "parse-group-" + UUID.randomUUID();
        var listener = listener(group);
        try {
            var receipt = publish(event);
            publish(event);
            waitUntil(() -> countJobs() == 1 && committed(group, receipt.partition()) >= 2);
            var storage = mock(UploadStorage.class);
            when(storage.open(any()))
                    .thenAnswer(
                            call ->
                                    new ByteArrayInputStream(
                                            "hello".getBytes(StandardCharsets.UTF_8)));
            new ParseWorker(
                            db.jobs,
                            db.uploads,
                            storage,
                            new ProcessTextExtractor(),
                            mock(UploadEvents.class))
                    .tick();
            assertThat(db.jobs.loadCurrent(event.tenant(), event.document()))
                    .extracting(ParsedDocument.Section::text)
                    .containsExactly("hello");
            assertThat(db.uploads.find(event.tenant(), event.document()).orElseThrow().status())
                    .isEqualTo(UploadStatus.PROCESSING);
            assertThat(
                            db.jdbc
                                    .sql(
                                            "SELECT count(*) FROM ingestion.document_outbox WHERE event_type = 'SOURCE_PARSED'")
                                    .query(Long.class)
                                    .single())
                    .isEqualTo(1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void databaseIntakeFailureDoesNotAdvanceKafkaOffsetAndRecoversAfterDatabaseReturns()
            throws Exception {
        var event = db.verified(UUID.randomUUID(), UUID.randomUUID());
        db.jdbc.sql("CREATE SEQUENCE ingestion.test_intake_attempts").update();
        db.jdbc
                .sql(
                        "ALTER TABLE ingestion.document_parse_jobs ADD CONSTRAINT test_intake_failure CHECK (nextval('ingestion.test_intake_attempts') < 0)")
                .update();
        String group = "parse-failure-" + UUID.randomUUID();
        var listener = listener(group);
        try {
            var receipt = publish(event);
            waitUntil(
                    () ->
                            db.jdbc
                                    .sql("SELECT is_called FROM ingestion.test_intake_attempts")
                                    .query(Boolean.class)
                                    .single());
            assertThat(countJobs()).isZero();
            assertThat(committed(group, receipt.partition())).isLessThan(receipt.offset() + 1);
            db.jdbc
                    .sql(
                            "ALTER TABLE ingestion.document_parse_jobs DROP CONSTRAINT test_intake_failure")
                    .update();
            waitUntil(
                    () ->
                            countJobs() == 1
                                    && committed(group, receipt.partition())
                                            >= receipt.offset() + 1);
        } finally {
            listener.stop();
            db.jdbc
                    .sql(
                            "ALTER TABLE ingestion.document_parse_jobs DROP CONSTRAINT IF EXISTS test_intake_failure")
                    .update();
            db.jdbc.sql("DROP SEQUENCE IF EXISTS ingestion.test_intake_attempts").update();
        }
    }

    private ConcurrentMessageListenerContainer<String, String> listener(String group) {
        var properties = new KafkaProperties();
        properties.setBootstrapServers(List.of(KAFKA.getBootstrapServers()));
        var factory = new ParserConfiguration(true, true).documentParseListenerFactory(properties);
        var listener = factory.createContainer(topic);
        listener.getContainerProperties().setGroupId(group);
        listener.getContainerProperties().setPollTimeout(100);
        var consumer = new ParseEventConsumer(db.jobs);
        listener.getContainerProperties()
                .setMessageListener((MessageListener<String, String>) consumer::receive);
        listener.start();
        return listener;
    }

    private OutboxDelivery.Receipt publish(ParseEvent event) {
        return new KafkaDocumentPublisher(template, JsonMapper.builder().build(), topic)
                .publish(
                        new OutboxDelivery(
                                event.id(),
                                event.tenant(),
                                event.document(),
                                event.version(),
                                "CONTENT_VERIFIED",
                                event.occurredAt(),
                                1,
                                UUID.randomUUID(),
                                1));
    }

    private long countJobs() {
        return db.jdbc
                .sql("SELECT count(*) FROM ingestion.document_parse_jobs")
                .query(Long.class)
                .single();
    }

    private long committed(String group, int partition) {
        try (var admin = admin()) {
            var offset =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS)
                            .get(new TopicPartition(topic, partition));
            return offset == null ? -1 : offset.offset();
        } catch (Exception pending) {
            return -1;
        }
    }

    private AdminClient admin() {
        return AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
