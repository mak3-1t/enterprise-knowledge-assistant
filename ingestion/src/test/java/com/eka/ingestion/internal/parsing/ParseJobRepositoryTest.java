package com.eka.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.internal.persistence.UploadStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ParseJobRepositoryTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static ParseDatabase db;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @BeforeAll
    static void database() {
        db = new ParseDatabase(POSTGRES);
    }

    @AfterAll
    static void close() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() {
        db.reset();
    }

    @Test
    void duplicateDeliveryCommitsOnePrivateSourceAndHandoffWithoutPrematureReady() {
        var event = db.verified(tenant, user);
        db.accept(event);
        db.accept(event);
        var job = db.jobs.claim().orElseThrow();
        assertThat(db.jobs.complete(job, parsed())).isTrue();
        assertThat(db.jobs.complete(job, parsed())).isFalse();
        db.accept(event);
        assertThat(count("document_parse_jobs")).isEqualTo(1);
        assertThat(db.jobs.loadCurrent(tenant, event.document()))
                .extracting(ParsedDocument.Section::text)
                .containsExactly("hello");
        assertThat(db.jobs.loadCurrent(UUID.randomUUID(), event.document())).isEmpty();
        assertThat(db.uploads.find(tenant, event.document()).orElseThrow().status())
                .isEqualTo(UploadStatus.PROCESSING);
        assertThat(
                        db.jdbc
                                .sql(
                                        "SELECT count(*) FROM ingestion.document_outbox WHERE event_type = 'SOURCE_PARSED'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void forgedTenantAndStaleEventsNeverCreateParseWork() {
        var event = db.verified(tenant, user);
        UUID foreign = UUID.randomUUID();
        db.jobs.accept(
                "test-events",
                0,
                2,
                foreign + "/" + event.document(),
                ParseIntakeTest.payload(
                        event.id(),
                        foreign,
                        event.document(),
                        event.version(),
                        event.occurredAt()));
        assertThat(count("document_parse_jobs")).isZero();
        assertThat(count("document_intake_rejections")).isEqualTo(1);
        db.uploads.delete(tenant, user, false, event.document());
        db.accept(event);
        assertThat(count("document_parse_jobs")).isZero();
        assertThat(count("document_intake_rejections")).isEqualTo(2);
    }

    @Test
    void concurrentClaimAndExpiredLeaseCannotPublishDuplicateSources() {
        var event = db.verified(tenant, user);
        db.accept(event);
        var a = CompletableFuture.supplyAsync(db.jobs::claim);
        var b = CompletableFuture.supplyAsync(db.jobs::claim);
        var one = a.join();
        var two = b.join();
        assertThat(one.isPresent() ^ two.isPresent()).isTrue();
        var stale = one.orElseGet(two::orElseThrow);
        db.jdbc
                .sql(
                        "UPDATE ingestion.document_parse_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second'")
                .update();
        var current = db.jobs.claim().orElseThrow();
        assertThat(db.jobs.complete(stale, parsed())).isFalse();
        assertThat(db.jobs.complete(current, parsed())).isTrue();
        assertThat(count("document_source_sections")).isEqualTo(1);
    }

    @Test
    void deletionRemovesDerivedTextAndRestoreCannotUseOldGeneration() {
        var event = db.verified(tenant, user);
        db.accept(event);
        var job = db.jobs.claim().orElseThrow();
        db.jobs.complete(job, parsed());
        db.uploads.delete(tenant, user, false, event.document());
        assertThat(db.jobs.loadCurrent(tenant, event.document())).isEmpty();
        assertThat(count("document_source_sections")).isZero();
        db.uploads.restore(tenant, user, false, event.document());
        assertThat(db.jobs.complete(job, parsed())).isFalse();
        assertThat(db.jobs.loadCurrent(tenant, event.document())).isEmpty();
    }

    @Test
    void crashedFourthAttemptAndPermanentEmptyExtractionFailClosed() {
        var event = db.verified(tenant, user);
        db.accept(event);
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(db.jobs.claim().orElseThrow().attempts()).isEqualTo(attempt);
            db.jdbc
                    .sql(
                            "UPDATE ingestion.document_parse_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second'")
                    .update();
        }
        assertThat(db.jobs.claim()).isEmpty();
        assertThat(db.uploads.find(tenant, event.document()).orElseThrow().status())
                .isEqualTo(UploadStatus.FAILED);
        var another = db.verified(tenant, user);
        db.accept(another);
        db.jobs.failed(db.jobs.claim().orElseThrow(), "EXTRACT_EMPTY", true);
        assertThat(db.uploads.find(tenant, another.document()).orElseThrow().errorCode())
                .isEqualTo("EXTRACT_EMPTY");
    }

    @Test
    void outboxFailureRollsBackSourcesPointerAndJobTogether() {
        var event = db.verified(tenant, user);
        db.accept(event);
        var job = db.jobs.claim().orElseThrow();
        db.jdbc
                .sql(
                        "ALTER TABLE ingestion.document_outbox ADD CONSTRAINT test_parse_failure CHECK (event_type <> 'SOURCE_PARSED')")
                .update();
        try {
            assertThatThrownBy(() -> db.jobs.complete(job, parsed()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("document_source_sections")).isZero();
            assertThat(db.uploads.find(tenant, event.document()).orElseThrow().version())
                    .isEqualTo(event.version());
        } finally {
            db.jdbc
                    .sql("ALTER TABLE ingestion.document_outbox DROP CONSTRAINT test_parse_failure")
                    .update();
        }
        assertThat(db.jobs.complete(job, parsed())).isTrue();
    }

    @Test
    void restoredDocumentRequiresNewScanAndOldRunningParserCannotPublish() {
        var first = db.verified(tenant, user);
        db.accept(first);
        var stale = db.jobs.claim().orElseThrow();
        db.uploads.delete(tenant, user, false, first.document());
        db.uploads.restore(tenant, user, false, first.document());
        assertThat(db.jobs.complete(stale, parsed())).isFalse();
        assertThat(count("document_source_sections")).isZero();
        db.scans.dispatch();
        db.scans.clean(db.scans.claim().orElseThrow());
        var current = db.event(tenant, first.document());
        assertThat(current.version()).isGreaterThan(first.version());
        db.accept(current);
        assertThat(db.jobs.complete(db.jobs.claim().orElseThrow(), parsed())).isTrue();
        assertThat(
                        db.jdbc
                                .sql("SELECT generation FROM ingestion.document_source_sections")
                                .query(Long.class)
                                .single())
                .isEqualTo(current.version());
        assertThat(db.jobs.complete(stale, parsed())).isFalse();
    }

    private long count(String table) {
        return db.jdbc.sql("SELECT count(*) FROM ingestion." + table).query(Long.class).single();
    }

    private static ParsedDocument parsed() {
        return new ParsedDocument(
                "apache-tika:3.3.3", List.of(new ParsedDocument.Section(null, "hello")));
    }
}
