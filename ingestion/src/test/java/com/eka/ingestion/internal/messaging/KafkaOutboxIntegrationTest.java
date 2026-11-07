package com.eka.ingestion.internal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.eka.ingestion.internal.service.UploadEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class KafkaOutboxIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.0.0").withStartupTimeout(Duration.ofMinutes(3));

    private static RelayDatabase db;
    private static DefaultKafkaProducerFactory<String, String> factory;
    private static KafkaTemplate<String, String> template;
    private final JsonMapper json = JsonMapper.builder().build();
    private String topic;

    @BeforeAll
    static void setup() {
        db = new RelayDatabase(POSTGRES);
        var properties = new KafkaProperties();
        properties.setBootstrapServers(List.of(KAFKA.getBootstrapServers()));
        factory = new KafkaOutboxConfiguration().outboxProducerFactory(properties);
        template = new KafkaTemplate<>(factory);
    }

    @AfterAll
    static void close() {
        if (template != null) {
            template.destroy();
        }
        if (factory != null) {
            factory.destroy();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() throws Exception {
        db.reset();
        topic = "eka-relay-" + UUID.randomUUID();
        try (var admin =
                AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void committedOutboxReachesRealKafkaBeforeDatabaseMarksPublished() {
        UUID tenant = UUID.randomUUID();
        UUID document = db.complete(tenant, UUID.randomUUID());
        var publisher = new KafkaDocumentPublisher(template, json, topic);
        new OutboxRelay(db.relay, publisher, mock(UploadEvents.class)).tick();
        var records = receive(1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(tenant + "/" + document);
        var body = json.readTree(records.getFirst().value());
        assertThat(body.get("tenantId").asText()).isEqualTo(tenant.toString());
        assertThat(body.get("documentId").asText()).isEqualTo(document.toString());
        assertThat(body.get("eventType").asText()).isEqualTo("UPLOAD_COMPLETED");
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(
                        db.jdbc
                                .sql(
                                        "SELECT published_at IS NOT NULL FROM ingestion.document_outbox")
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        db.jdbc
                                .sql("SELECT broker_offset FROM ingestion.document_outbox")
                                .query(Long.class)
                                .single())
                .isEqualTo(records.getFirst().offset());
        assertThat(db.relay.claim()).isEmpty();
    }

    @Test
    void crashAfterBrokerAppendBeforeDatabaseAcknowledgementRedeliversSameEventId() {
        db.complete(UUID.randomUUID(), UUID.randomUUID());
        var publisher = new KafkaDocumentPublisher(template, json, topic);
        var first = db.relay.claim().orElseThrow();
        publisher.publish(first);
        // Simulate process death: broker acknowledged, but no database acknowledgement happened.
        db.jdbc
                .sql(
                        "UPDATE ingestion.document_outbox SET relay_lease_until = clock_timestamp() - INTERVAL '1 second'")
                .update();
        var recovered = db.relay.claim().orElseThrow();
        var receipt = publisher.publish(recovered);
        assertThat(db.relay.acknowledge(recovered, receipt)).isTrue();
        assertThat(db.relay.acknowledge(first, receipt)).isFalse();
        var records = receive(2);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
        assertThat(records.get(0).value()).isEqualTo(records.get(1).value());
        assertThat(json.readTree(records.get(0).value()).get("eventId").asText())
                .isEqualTo(first.id().toString());
        assertThat(db.relay.claim()).isEmpty();
    }

    private List<ConsumerRecord<String, String>> receive(int expected) {
        var config =
                Map.<String, Object>of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "relay-test-" + UUID.randomUUID(),
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            var partitions =
                    List.of(
                            new TopicPartition(topic, 0),
                            new TopicPartition(topic, 1),
                            new TopicPartition(topic, 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var results = new ArrayList<ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (results.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(results::add);
            }
            return results;
        }
    }
}
