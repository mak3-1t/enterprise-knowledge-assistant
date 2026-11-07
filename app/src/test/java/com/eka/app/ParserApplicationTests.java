package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.eka.ingestion.internal.parsing.ParseJobRepository;
import com.eka.ingestion.internal.parsing.TextExtractor;
import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.scan.ScanJobRepository;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real application wiring, Kafka, PostgreSQL and child parser; storage/scan engine tested
 * separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ParserApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    private static final String TOPIC = "parser-app-" + UUID.randomUUID();
    @Autowired UploadRepository uploads;
    @Autowired ScanJobRepository scans;
    @Autowired ParseJobRepository parses;
    @Autowired JdbcClient jdbc;
    @Autowired TextExtractor extractor;
    @MockitoBean UploadStorage storage;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestSigningKeys.configure(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("eka.ingestion.kafka.enabled", () -> "true");
        registry.add("eka.ingestion.kafka.create-topic", () -> "true");
        registry.add("eka.ingestion.kafka.topic", () -> TOPIC);
        registry.add("eka.ingestion.parser.enabled", () -> "true");
        registry.add("eka.ingestion.parser.group-id", () -> "app-test-" + TOPIC);
        registry.add("eka.upload.enabled", () -> "true");
        registry.add("eka.upload.scan.enabled", () -> "false");
        registry.add("eka.upload.access-key", () -> "test-access");
        registry.add("eka.upload.secret-key", () -> "test-secret");
    }

    @Test
    void scheduledRelayListenerAndWorkerPersistPrivateSourceWithoutPublishingReady()
            throws Exception {
        when(storage.open(any()))
                .thenAnswer(
                        call -> new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        var row =
                uploads.initiate(
                        tenant,
                        user,
                        "app-test-" + UUID.randomUUID(),
                        "file.txt",
                        DocumentFormat.TXT,
                        5,
                        null);
        uploads.complete(
                tenant,
                user,
                row.id(),
                5,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        scans.dispatch();
        assertThat(scans.clean(scans.claim().orElseThrow())).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (parses.loadCurrent(tenant, row.id()).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(parses.loadCurrent(tenant, row.id()))
                .extracting(com.eka.ingestion.internal.parsing.ParsedDocument.Section::text)
                .containsExactly("hello");
        assertThat(parses.loadCurrent(UUID.randomUUID(), row.id())).isEmpty();
        assertThat(uploads.find(tenant, row.id()).orElseThrow().status())
                .isEqualTo(UploadStatus.PROCESSING);
        assertThat(extractor)
                .isInstanceOf(com.eka.ingestion.internal.parsing.ProcessTextExtractor.class);
        assertThat(
                        jdbc.sql(
                                        "SELECT state FROM ingestion.document_parse_jobs WHERE document_id = ?")
                                .param(row.id())
                                .query(String.class)
                                .single())
                .isEqualTo("SUCCEEDED");
    }
}
