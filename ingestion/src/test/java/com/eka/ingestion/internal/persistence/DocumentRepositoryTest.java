package com.eka.ingestion.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DocumentRepositoryTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String HASH = "a".repeat(64);
    private static JdbcClient jdbc;
    private static Flyway flyway;
    private DocumentRepository documents;
    private UUID tenantA;
    private UUID tenantB;
    private UUID uploader;

    @BeforeAll
    static void database() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway = Flyway.configure().dataSource(source).cleanDisabled(true).load();
        flyway.migrate();
        jdbc = JdbcClient.create(source);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM ingestion.documents").update();
        documents = new DocumentRepository(jdbc);
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        uploader = UUID.randomUUID();
    }

    @Test
    void uploadIntentIsHiddenUntilCompletedAndMigrationReplayPreservesMetadata() {
        var pending = create(tenantA, "guide.pdf", DocumentFormat.PDF);
        assertThat(pending.storageState()).isEqualTo(StorageState.UPLOADING);
        assertThat(pending.byteSize()).isNull();
        assertThat(pending.ingestionStatus()).isNull();
        assertThat(pending.createdAt()).isNotNull();
        assertThat(pending.objectKey())
                .isEqualTo("tenants/" + tenantA + "/documents/" + pending.id() + "/original");
        assertThat(documents.findStored(tenantA, pending.id())).isEmpty();
        assertThat(documents.listStored(tenantA, 20, 0)).isEmpty();
        assertThat(
                        documents.completeUpload(
                                tenantA, pending.id(), 0, DocumentRepository.MAX_FILE_BYTES, HASH))
                .isTrue();
        var stored = documents.findStored(tenantA, pending.id()).orElseThrow();
        assertThat(stored.byteSize()).isEqualTo(DocumentRepository.MAX_FILE_BYTES);
        assertThat(stored.sha256()).isEqualTo(HASH);
        assertThat(stored.ingestionStatus()).isEqualTo(IngestionStatus.UPLOADED);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.uploadedAt()).isNotNull();
        assertThat(stored.uploadedBy()).isEqualTo(uploader);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(documents.findStored(tenantA, pending.id())).contains(stored);
        assertThat(stored.toString()).doesNotContain(stored.objectKey(), stored.filename(), HASH);
    }

    @Test
    void bothTenantDirectionsCannotReadListCompleteOrFailForeignDocuments() {
        var a = create(tenantA, "a.txt", DocumentFormat.TXT);
        var b = create(tenantB, "b.md", DocumentFormat.MD);
        assertThat(documents.completeUpload(tenantA, b.id(), 0, 10, HASH)).isFalse();
        assertThat(documents.completeUpload(tenantB, a.id(), 0, 10, HASH)).isFalse();
        assertThat(documents.failUpload(tenantA, b.id(), 0)).isFalse();
        assertThat(documents.failUpload(tenantB, a.id(), 0)).isFalse();
        assertThat(documents.findUpload(tenantA, b.id())).isEmpty();
        assertThat(documents.findUpload(tenantB, a.id())).isEmpty();
        documents.completeUpload(tenantA, a.id(), 0, 10, HASH);
        documents.completeUpload(tenantB, b.id(), 0, 10, HASH);
        assertThat(documents.findStored(tenantA, b.id())).isEmpty();
        assertThat(documents.findStored(tenantB, a.id())).isEmpty();
        assertThat(documents.findStored(tenantA, UUID.randomUUID())).isEmpty();
        assertThat(documents.listStored(tenantA, 20, 0))
                .extracting(DocumentRecord::id)
                .containsExactly(a.id());
        assertThat(documents.listStored(tenantB, 20, 0))
                .extracting(DocumentRecord::id)
                .containsExactly(b.id());
    }

    @Test
    void duplicateContentProducesIndependentDocumentsKeysAndOwnership() {
        var first = create(tenantA, "same.docx", DocumentFormat.DOCX);
        var second = create(tenantA, "same.docx", DocumentFormat.DOCX);
        documents.completeUpload(tenantA, first.id(), 0, 42, HASH);
        documents.completeUpload(tenantA, second.id(), 0, 42, HASH);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
        assertThat(documents.listStored(tenantA, 20, 0))
                .hasSize(2)
                .allMatch(row -> row.sha256().equals(HASH));
    }

    @Test
    void databaseEnforcesLimitsFormatKeysAndCompleteStorageMetadata() {
        var pending = create(tenantA, "safe.pdf", DocumentFormat.PDF);
        for (String update :
                List.of(
                        "format = 'EXE'",
                        "filename = '../unsafe.pdf'",
                        "object_key = 'foreign-key'",
                        "byte_size = 20971521",
                        "sha256 = 'not-a-hash'",
                        "version = -1",
                        "storage_state = 'STORED'",
                        "ingestion_status = 'UPLOADED'")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE ingestion.documents SET "
                                                            + update
                                                            + " WHERE id = ?")
                                            .param(pending.id())
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(documents.findUpload(tenantA, pending.id())).contains(pending);
        for (DocumentFormat format : DocumentFormat.values()) {
            assertThat(create(tenantA, "format-file", format).format()).isEqualTo(format);
        }
    }

    @Test
    void paginationIsStableAndFiltersTenantAndHiddenStatesBeforeLimit() {
        var first = create(tenantA, "one.txt", DocumentFormat.TXT);
        var second = create(tenantA, "two.txt", DocumentFormat.TXT);
        create(tenantA, "pending.txt", DocumentFormat.TXT);
        var failed = create(tenantA, "failed.txt", DocumentFormat.TXT);
        documents.failUpload(tenantA, failed.id(), 0);
        var other = create(tenantB, "other.txt", DocumentFormat.TXT);
        for (var pair : List.of(first, second, other)) {
            documents.completeUpload(pair.tenantId(), pair.id(), 0, 10, HASH);
        }
        var page0 = documents.listStored(tenantA, 1, 0);
        var page1 = documents.listStored(tenantA, 1, 1);
        assertThat(page0).hasSize(1);
        assertThat(page1).hasSize(1);
        assertThat(page0.getFirst().id()).isNotEqualTo(page1.getFirst().id());
        assertThat(documents.listStored(tenantA, 1, 0)).isEqualTo(page0);
        assertThat(documents.listStored(tenantA, 100, 2)).isEmpty();
        assertThat(documents.listStored(tenantB, 20, 0))
                .extracting(DocumentRecord::id)
                .containsExactly(other.id());
    }

    @Test
    void failedUploadsStayHiddenAndCannotBeCompletedWithStaleVersion() {
        var pending = create(tenantA, "failed.pdf", DocumentFormat.PDF);
        assertThat(documents.failUpload(tenantA, pending.id(), 1)).isFalse();
        assertThat(documents.failUpload(tenantA, pending.id(), 0)).isTrue();
        var failed = documents.findUpload(tenantA, pending.id()).orElseThrow();
        assertThat(failed.storageState()).isEqualTo(StorageState.FAILED);
        assertThat(failed.version()).isEqualTo(1);
        assertThat(documents.failUpload(tenantA, pending.id(), 0)).isFalse();
        assertThat(documents.completeUpload(tenantA, pending.id(), 0, 10, HASH)).isFalse();
        assertThat(documents.completeUpload(tenantA, pending.id(), 1, 10, HASH)).isFalse();
        assertThat(documents.findStored(tenantA, pending.id())).isEmpty();
    }

    @Test
    void concurrentCompletionAndFailureHaveOneWinnerAndNoPartialState() {
        var pending = create(tenantA, "race.txt", DocumentFormat.TXT);
        var complete =
                CompletableFuture.supplyAsync(
                        () -> documents.completeUpload(tenantA, pending.id(), 0, 10, HASH));
        var fail =
                CompletableFuture.supplyAsync(() -> documents.failUpload(tenantA, pending.id(), 0));
        boolean completed = complete.join();
        boolean failed = fail.join();
        assertThat(completed ^ failed).isTrue();
        var current = documents.findUpload(tenantA, pending.id()).orElseThrow();
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.storageState())
                .isEqualTo(completed ? StorageState.STORED : StorageState.FAILED);
        assertThat(documents.findStored(tenantA, pending.id()).isPresent()).isEqualTo(completed);
    }

    @Test
    void missingScopeAndInvalidPaginationDoNotPermitUnscopedReads() {
        assertThatThrownBy(() -> documents.findStored(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> documents.listStored(null, 20, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> documents.listStored(tenantA, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> documents.listStored(tenantA, 101, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> documents.listStored(tenantA, 20, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(documents.listStored(UUID.randomUUID(), 20, 0)).isEmpty();
    }

    private DocumentRecord create(UUID tenant, String name, DocumentFormat format) {
        return documents.createUpload(tenant, UUID.randomUUID(), uploader, name, format);
    }
}
