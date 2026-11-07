package com.eka.ingestion.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class UploadRepositoryTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static final String HASH = "a".repeat(64);
    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static UploadRepository uploads;
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
        context.register(Transactions.class, UploadRepository.class);
        context.refresh();
        jdbc = context.getBean(JdbcClient.class);
        uploads = context.getBean(UploadRepository.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM ingestion.document_outbox").update();
        jdbc.sql("DELETE FROM ingestion.documents").update();
        jdbc.sql("DELETE FROM ingestion.upload_quotas").update();
    }

    @Test
    void concurrentSameKeyReservesOnceButNewKeyCreatesSeparateDocument() {
        var a = CompletableFuture.supplyAsync(() -> create("same-key", 100));
        var b = CompletableFuture.supplyAsync(() -> create("same-key", 100));
        assertThat(a.join().id()).isEqualTo(b.join().id());
        assertThat(create("new-key-1", 100).id()).isNotEqualTo(a.join().id());
        assertThat(count("reserved_files", "ingestion.upload_quotas")).isEqualTo(2);
        assertThat(count("reserved_bytes", "ingestion.upload_quotas")).isEqualTo(200);
        assertThatThrownBy(() -> create("same-key", 101)).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(count("reserved_files", "ingestion.upload_quotas")).isEqualTo(2);
    }

    @Test
    void concurrentQuotaHasOneWinnerAndRollbackDoesNotLeakReservation() {
        create("first-key", 100);
        jdbc.sql("UPDATE ingestion.upload_quotas SET reserved_bytes = 524287990").update();
        var a = CompletableFuture.supplyAsync(() -> tryCreate("last-key-a", 10));
        var b = CompletableFuture.supplyAsync(() -> tryCreate("last-key-b", 10));
        assertThat(a.join() ^ b.join()).isTrue();
        assertThat(count("reserved_bytes", "ingestion.upload_quotas")).isEqualTo(524288000);
        assertThat(count("count(*)", "ingestion.documents")).isEqualTo(2);
        jdbc.sql("UPDATE ingestion.upload_quotas SET reserved_bytes = 100, reserved_files = 100")
                .update();
        assertThatThrownBy(() -> create("over-count", 1)).hasMessage("QUOTA_EXCEEDED");
    }

    @Test
    void insertFailureRollsBackQuotaAndOutboxFailureRollsBackCompletion() {
        create("before-fail", 100);
        jdbc.sql(
                        "ALTER TABLE ingestion.documents ADD CONSTRAINT test_insert_failure CHECK (declared_size <> 123)")
                .update();
        try {
            assertThatThrownBy(() -> create("insert-fail", 123))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("reserved_bytes", "ingestion.upload_quotas")).isEqualTo(100);
        } finally {
            jdbc.sql("ALTER TABLE ingestion.documents DROP CONSTRAINT test_insert_failure")
                    .update();
        }
        var row = create("event-fail", 50);
        jdbc.sql(
                        "ALTER TABLE ingestion.document_outbox ADD CONSTRAINT test_event_failure CHECK (event_type <> 'UPLOAD_COMPLETED')")
                .update();
        try {
            assertThatThrownBy(() -> uploads.complete(tenant, user, row.id(), 50, HASH))
                    .isInstanceOf(RuntimeException.class);
            assertThat(uploads.find(tenant, row.id()).orElseThrow().status())
                    .isEqualTo(UploadStatus.PENDING);
        } finally {
            jdbc.sql("ALTER TABLE ingestion.document_outbox DROP CONSTRAINT test_event_failure")
                    .update();
        }
    }

    @Test
    void completeIsIdempotentAndCreatesExactlyOneEvent() {
        var row = create("complete-key", 100);
        var first = uploads.complete(tenant, user, row.id(), 100, HASH);
        var second = uploads.complete(tenant, user, row.id(), 100, HASH);
        assertThat(second).isEqualTo(first);
        assertThat(count("count(*)", "ingestion.document_outbox")).isEqualTo(1);
        assertThatThrownBy(() -> uploads.complete(tenant, user, row.id(), 100, "b".repeat(64)))
                .hasMessage("INVALID_STATE");
        assertThat(new DocumentRepository(jdbc).failUpload(tenant, row.id(), row.version()))
                .isFalse();
    }

    @Test
    void expiryAppliesBeforeCronAndCleanupReleasesOnlyAfterSuccessfulDeletion() {
        var row = create("expire-key", 100);
        jdbc.sql(
                        "UPDATE ingestion.documents SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
                .update();
        assertThatThrownBy(() -> uploads.complete(tenant, user, row.id(), 100, HASH))
                .hasMessage("UPLOAD_EXPIRED");
        assertThat(uploads.expirePending()).isEqualTo(1);
        assertThat(uploads.cleanupCandidates(10)).hasSize(1);
        assertThatThrownBy(
                        () ->
                                uploads.purge(
                                        tenant,
                                        row.id(),
                                        () -> {
                                            throw new IllegalStateException("storage unavailable");
                                        }))
                .hasMessage("storage unavailable");
        assertThat(count("reserved_bytes", "ingestion.upload_quotas")).isEqualTo(100);
        assertThat(uploads.find(tenant, row.id()).orElseThrow().purgedAt()).isNull();
        assertThat(uploads.purge(tenant, row.id(), () -> {})).isTrue();
        assertThat(
                        uploads.purge(
                                tenant,
                                row.id(),
                                () -> {
                                    throw new AssertionError();
                                }))
                .isFalse();
        assertThat(count("reserved_bytes", "ingestion.upload_quotas")).isZero();
        assertThat(count("reserved_files", "ingestion.upload_quotas")).isZero();
    }

    @Test
    void deleteBlocksStaleWorkersAndRestoreRequiresFreshScanningWhileRetainingQuota() {
        var row = create("delete-key", 100);
        var uploaded = uploads.complete(tenant, user, row.id(), 100, HASH);
        assertThat(
                        uploads.advance(
                                tenant,
                                row.id(),
                                uploaded.version(),
                                UploadStatus.UPLOADED,
                                UploadStatus.SCANNING))
                .isTrue();
        var scanning = uploads.find(tenant, row.id()).orElseThrow();
        uploads.delete(tenant, user, false, row.id());
        uploads.delete(tenant, user, false, row.id());
        assertThat(
                        uploads.advance(
                                tenant,
                                row.id(),
                                scanning.version(),
                                UploadStatus.SCANNING,
                                UploadStatus.PROCESSING))
                .isFalse();
        assertThat(uploads.list(tenant, 10, 0)).isEmpty();
        assertThat(count("reserved_bytes", "ingestion.upload_quotas")).isEqualTo(100);
        assertThat(uploads.cleanupCandidates(10)).isEmpty();
        var restored = uploads.restore(tenant, user, false, row.id());
        assertThat(restored.status()).isEqualTo(UploadStatus.UPLOADED);
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.version()).isGreaterThan(scanning.version());
        assertThat(count("count(*)", "ingestion.document_outbox")).isEqualTo(3);
    }

    @Test
    void failedRejectedCancelledAndRetentionExpiredDocumentsCannotRestore() {
        var row = create("reject-key", 10);
        var uploaded = uploads.complete(tenant, user, row.id(), 10, HASH);
        assertThat(uploads.fail(tenant, row.id(), uploaded.version(), true, "MALWARE_DETECTED"))
                .isTrue();
        uploads.delete(tenant, user, false, row.id());
        assertThatThrownBy(() -> uploads.restore(tenant, user, false, row.id()))
                .hasMessage("RESTORE_UNAVAILABLE");
        var pending = create("cancel-key", 10);
        uploads.delete(tenant, user, false, pending.id());
        assertThatThrownBy(() -> uploads.complete(tenant, user, pending.id(), 10, HASH))
                .hasMessage("INVALID_STATE");
        assertThatThrownBy(() -> uploads.restore(tenant, user, false, pending.id()))
                .hasMessage("RESTORE_UNAVAILABLE");
        var expired = create("retained-key", 10);
        uploads.complete(tenant, user, expired.id(), 10, HASH);
        uploads.delete(tenant, user, false, expired.id());
        jdbc.sql(
                        "UPDATE ingestion.documents SET cleanup_after = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?")
                .param(expired.id())
                .update();
        assertThatThrownBy(() -> uploads.restore(tenant, user, false, expired.id()))
                .hasMessage("RESTORE_UNAVAILABLE");
    }

    @Test
    void foreignTenantAndNonOwnerCannotMutateButSharedTenantCanRead() {
        var row = create("tenant-key", 10);
        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        assertThat(uploads.find(otherTenant, row.id())).isEmpty();
        assertThat(uploads.list(otherTenant, 10, 0)).isEmpty();
        assertThatThrownBy(() -> uploads.complete(otherTenant, user, row.id(), 10, HASH))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> uploads.delete(otherTenant, user, true, row.id()))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> uploads.complete(tenant, otherUser, row.id(), 10, HASH))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> uploads.delete(tenant, otherUser, false, row.id()))
                .hasMessage("NOT_FOUND");
        assertThat(uploads.find(tenant, row.id())).contains(row);
        uploads.delete(tenant, otherUser, true, row.id());
        assertThat(uploads.find(tenant, row.id()).orElseThrow().status())
                .isEqualTo(UploadStatus.DELETED);
    }

    private UploadRecord create(String key, long bytes) {
        return uploads.initiate(tenant, user, key, "guide.pdf", DocumentFormat.PDF, bytes, null);
    }

    private boolean tryCreate(String key, long bytes) {
        try {
            create(key, bytes);
            return true;
        } catch (UploadConflict error) {
            assertThat(error).hasMessage("QUOTA_EXCEEDED");
            return false;
        }
    }

    private long count(String expression, String table) {
        return jdbc.sql("SELECT " + expression + " FROM " + table).query(Long.class).single();
    }
}
