package com.eka.ingestion.internal.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ScanJobRepositoryTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static UploadRepository uploads;
    private static ScanJobRepository jobs;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    @BeforeAll
    static void database() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcClient.class, () -> JdbcClient.create(source));
        context.registerBean(
                PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.register(Transactions.class, UploadRepository.class, ScanJobRepository.class);
        context.refresh();
        jdbc = context.getBean(JdbcClient.class);
        uploads = context.getBean(UploadRepository.class);
        jobs = context.getBean(ScanJobRepository.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM ingestion.document_scan_jobs").update();
        jdbc.sql("DELETE FROM ingestion.document_outbox").update();
        jdbc.sql("DELETE FROM ingestion.documents").update();
        jdbc.sql("DELETE FROM ingestion.upload_quotas").update();
    }

    @Test
    void dispatchIsIdempotentAndDoesNotPretendKafkaWasPublished() {
        uploaded();
        assertThat(jobs.dispatch()).isEqualTo(1);
        assertThat(jobs.dispatch()).isZero();
        assertThat(count("SELECT count(*) FROM ingestion.document_scan_jobs")).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM ingestion.document_outbox WHERE published_at IS NOT NULL"))
                .isZero();
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndCleanIsAtomicIdempotentHandoffNotReady() {
        UUID id = uploaded();
        jobs.dispatch();
        var first = CompletableFuture.supplyAsync(jobs::claim);
        var second = CompletableFuture.supplyAsync(jobs::claim);
        var a = first.join();
        var b = second.join();
        assertThat(a.isPresent() ^ b.isPresent()).isTrue();
        var job = a.orElseGet(b::orElseThrow);
        assertThat(jobs.clean(job)).isTrue();
        assertThat(jobs.clean(job)).isFalse();
        assertThat(uploads.find(tenant, id).orElseThrow().status())
                .isEqualTo(UploadStatus.PROCESSING);
        assertThat(
                        count(
                                "SELECT count(*) FROM ingestion.document_outbox WHERE event_type = 'CONTENT_VERIFIED'"))
                .isEqualTo(1);
    }

    @Test
    void expiredLeaseIsReclaimedAndPreviousWorkerCannotPublish() {
        uploaded();
        jobs.dispatch();
        var stale = jobs.claim().orElseThrow();
        expireLease();
        var current = jobs.claim().orElseThrow();
        assertThat(current.leaseToken()).isNotEqualTo(stale.leaseToken());
        assertThat(current.attempts()).isEqualTo(2);
        assertThat(jobs.clean(stale)).isFalse();
        assertThat(jobs.retry(stale, "SCANNER_UNAVAILABLE")).isFalse();
        assertThat(jobs.clean(current)).isTrue();
    }

    @Test
    void scannerFailureRetriesThreeTimesThenFailsClosedInDurableDeadLetterState() {
        UUID id = uploaded();
        jobs.dispatch();
        for (int attempt = 1; attempt <= 4; attempt++) {
            var job = jobs.claim().orElseThrow();
            assertThat(job.attempts()).isEqualTo(attempt);
            assertThat(jobs.retry(job, "SCANNER_UNAVAILABLE")).isTrue();
            if (attempt < 4) {
                assertThat(uploads.find(tenant, id).orElseThrow().status())
                        .isEqualTo(UploadStatus.SCANNING);
                jdbc.sql(
                                "UPDATE ingestion.document_scan_jobs SET available_at = clock_timestamp() - INTERVAL '1 second'")
                        .update();
            }
        }
        assertThat(jobs.claim()).isEmpty();
        var failed = uploads.find(tenant, id).orElseThrow();
        assertThat(failed.status()).isEqualTo(UploadStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SCANNER_UNAVAILABLE");
        assertThat(state()).isEqualTo("DEAD");
        assertThat(uploads.cleanupCandidates(100)).hasSize(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM ingestion.document_outbox WHERE event_type = 'CONTENT_VERIFIED'"))
                .isZero();
    }

    @Test
    void repeatedCrashAtFourthLeaseCannotLeaveDocumentStuckForever() {
        UUID id = uploaded();
        jobs.dispatch();
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(jobs.claim().orElseThrow().attempts()).isEqualTo(attempt);
            expireLease();
        }
        assertThat(jobs.claim()).isEmpty();
        assertThat(state()).isEqualTo("DEAD");
        assertThat(uploads.find(tenant, id).orElseThrow().errorCode())
                .isEqualTo("SCAN_RETRY_EXHAUSTED");
    }

    @Test
    void deleteAndRestoreInvalidatePreviousScanGeneration() {
        UUID id = uploaded();
        jobs.dispatch();
        var stale = jobs.claim().orElseThrow();
        uploads.delete(tenant, user, false, id);
        var restored = uploads.restore(tenant, user, false, id);
        jobs.dispatch();
        assertThat(jobs.clean(stale)).isFalse();
        assertThat(jobs.rejected(stale, "MALWARE_DETECTED")).isFalse();
        var current = jobs.claim().orElseThrow();
        assertThat(current.generation()).isEqualTo(restored.version());
        assertThat(jobs.clean(current)).isTrue();
        assertThat(uploads.find(tenant, id).orElseThrow().status())
                .isEqualTo(UploadStatus.PROCESSING);
    }

    @Test
    void malformedOrMaliciousFilesAreRejectedWithoutProcessingHandoff() {
        UUID id = uploaded();
        jobs.dispatch();
        var job = jobs.claim().orElseThrow();
        assertThat(jobs.rejected(job, "MALWARE_DETECTED")).isTrue();
        assertThat(jobs.clean(job)).isFalse();
        assertThat(state()).isEqualTo("REJECTED");
        var rejected = uploads.find(tenant, id).orElseThrow();
        assertThat(rejected.status()).isEqualTo(UploadStatus.REJECTED);
        assertThat(rejected.errorCode()).isEqualTo("MALWARE_DETECTED");
        assertThat(
                        count(
                                "SELECT count(*) FROM ingestion.document_outbox WHERE event_type = 'CONTENT_VERIFIED'"))
                .isZero();
    }

    @Test
    void failedJobClaimAndFailedHandoffRollBackDocumentAndJobTogether() {
        UUID id = uploaded();
        jobs.dispatch();
        jdbc.sql(
                        "ALTER TABLE ingestion.document_scan_jobs ADD CONSTRAINT test_claim_failure CHECK (attempts <> 1)")
                .update();
        try {
            assertThatThrownBy(jobs::claim).isInstanceOf(RuntimeException.class);
            assertThat(uploads.find(tenant, id).orElseThrow().status())
                    .isEqualTo(UploadStatus.UPLOADED);
            assertThat(state()).isEqualTo("PENDING");
        } finally {
            jdbc.sql("ALTER TABLE ingestion.document_scan_jobs DROP CONSTRAINT test_claim_failure")
                    .update();
        }
        var job = jobs.claim().orElseThrow();
        jdbc.sql(
                        "ALTER TABLE ingestion.document_outbox ADD CONSTRAINT test_handoff_failure CHECK (event_type <> 'CONTENT_VERIFIED')")
                .update();
        try {
            assertThatThrownBy(() -> jobs.clean(job)).isInstanceOf(RuntimeException.class);
            assertThat(uploads.find(tenant, id).orElseThrow().status())
                    .isEqualTo(UploadStatus.SCANNING);
            assertThat(state()).isEqualTo("RUNNING");
        } finally {
            jdbc.sql("ALTER TABLE ingestion.document_outbox DROP CONSTRAINT test_handoff_failure")
                    .update();
        }
        assertThat(jobs.clean(job)).isTrue();
    }

    private UUID uploaded() {
        var row =
                uploads.initiate(
                        tenant,
                        user,
                        "request-" + UUID.randomUUID(),
                        "file.txt",
                        DocumentFormat.TXT,
                        5,
                        null);
        uploads.complete(tenant, user, row.id(), 5, "a".repeat(64));
        return row.id();
    }

    private void expireLease() {
        jdbc.sql(
                        "UPDATE ingestion.document_scan_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE state = 'RUNNING'")
                .update();
    }

    private String state() {
        return jdbc.sql("SELECT state FROM ingestion.document_scan_jobs")
                .query(String.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
