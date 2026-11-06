package com.eka.ingestion.internal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OutboxRelayRepositoryTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static RelayDatabase db;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @BeforeAll
    static void database() {
        db = new RelayDatabase(POSTGRES);
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
    void uncommittedOrRolledBackCompletionIsInvisibleToConcurrentRelay() {
        UUID id = db.pending(tenant, user);
        new TransactionTemplate(db.transactions)
                .executeWithoutResult(
                        transaction -> {
                            db.uploads.complete(tenant, user, id, 5, "a".repeat(64));
                            try {
                                assertThat(
                                                CompletableFuture.supplyAsync(db.relay::claim)
                                                        .get(5, TimeUnit.SECONDS))
                                        .isEmpty();
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                            transaction.setRollbackOnly();
                        });
        assertThat(db.relay.claim()).isEmpty();
        assertThat(
                        db.jdbc
                                .sql("SELECT count(*) FROM ingestion.document_outbox")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void concurrentRelaysClaimDifferentDocumentsWithoutCrossTenantConfusion() {
        UUID a = db.complete(tenant, user);
        UUID otherTenant = UUID.randomUUID();
        UUID b = db.complete(otherTenant, UUID.randomUUID());
        var first = CompletableFuture.supplyAsync(db.relay::claim);
        var second = CompletableFuture.supplyAsync(db.relay::claim);
        var one = first.join().orElseThrow();
        var two = second.join().orElseThrow();
        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(java.util.Set.of(one.documentId(), two.documentId()))
                .containsExactlyInAnyOrder(a, b);
        for (var event : java.util.List.of(one, two)) {
            assertThat(event.tenantId())
                    .isEqualTo(event.documentId().equals(a) ? tenant : otherTenant);
        }
        assertThat(db.relay.claim()).isEmpty();
    }

    @Test
    void onlyCurrentUnexpiredLeaseCanRecordBrokerReceiptExactlyOnce() {
        db.complete(tenant, user);
        assertThatThrownBy(
                        () ->
                                db.jdbc
                                        .sql(
                                                "UPDATE ingestion.document_outbox SET published_at = clock_timestamp()")
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        var old = db.relay.claim().orElseThrow();
        db.jdbc
                .sql(
                        "UPDATE ingestion.document_outbox SET relay_lease_until = clock_timestamp() - INTERVAL '1 second'")
                .update();
        assertThat(db.relay.acknowledge(old, new OutboxDelivery.Receipt(0, 1))).isFalse();
        var current = db.relay.claim().orElseThrow();
        assertThat(current.id()).isEqualTo(old.id());
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(current.attempts()).isEqualTo(2);
        assertThat(db.relay.defer(old)).isFalse();
        assertThat(db.relay.acknowledge(current, new OutboxDelivery.Receipt(2, 100))).isTrue();
        assertThat(db.relay.acknowledge(current, new OutboxDelivery.Receipt(1, 200))).isFalse();
        assertThat(
                        db.jdbc
                                .sql("SELECT broker_offset FROM ingestion.document_outbox")
                                .query(Long.class)
                                .single())
                .isEqualTo(100);
        assertThat(db.relay.claim()).isEmpty();
    }

    @Test
    void laterDocumentEventsWaitForEarlierAcknowledgementIncludingDuringBackoff() {
        UUID id = db.complete(tenant, user);
        db.uploads.delete(tenant, user, false, id);
        var first = db.relay.claim().orElseThrow();
        assertThat(first.eventType()).isEqualTo("UPLOAD_COMPLETED");
        assertThat(db.relay.claim()).isEmpty();
        assertThat(db.relay.defer(first)).isTrue();
        assertThat(db.relay.claim()).isEmpty();
        db.jdbc
                .sql(
                        "UPDATE ingestion.document_outbox SET relay_available_at = clock_timestamp() - INTERVAL '1 second'")
                .update();
        var retry = db.relay.claim().orElseThrow();
        assertThat(retry.id()).isEqualTo(first.id());
        db.relay.acknowledge(retry, new OutboxDelivery.Receipt(0, 1));
        var next = db.relay.claim().orElseThrow();
        assertThat(next.eventType()).isEqualTo("DOCUMENT_DELETED");
        assertThat(next.documentVersion()).isGreaterThan(first.documentVersion());
    }

    @Test
    void repeatedBrokerFailureCapsBackoffWithoutDroppingCommittedEvent() {
        db.complete(tenant, user);
        db.jdbc.sql("UPDATE ingestion.document_outbox SET relay_attempts = 10000").update();
        var delivery = db.relay.claim().orElseThrow();
        assertThat(db.relay.defer(delivery)).isTrue();
        assertThat(
                        db.jdbc
                                .sql("SELECT relay_attempts FROM ingestion.document_outbox")
                                .query(Long.class)
                                .single())
                .isEqualTo(10001);
        assertThat(
                        db.jdbc
                                .sql("SELECT published_at IS NULL FROM ingestion.document_outbox")
                                .query(Boolean.class)
                                .single())
                .isTrue();
        double delay =
                db.jdbc
                        .sql(
                                "SELECT extract(epoch FROM relay_available_at - clock_timestamp()) FROM ingestion.document_outbox")
                        .query(Double.class)
                        .single();
        assertThat(delay).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    void wrongTenantCannotAcknowledgeOrDeferAndDatabaseRejectsInconsistentIdentity() {
        UUID id = db.complete(tenant, user);
        var valid = db.relay.claim().orElseThrow();
        var wrong =
                new OutboxDelivery(
                        valid.id(),
                        UUID.randomUUID(),
                        valid.documentId(),
                        valid.documentVersion(),
                        valid.eventType(),
                        valid.occurredAt(),
                        valid.schemaVersion(),
                        valid.leaseToken(),
                        valid.attempts());
        assertThat(db.relay.acknowledge(wrong, new OutboxDelivery.Receipt(0, 1))).isFalse();
        assertThat(db.relay.defer(wrong)).isFalse();
        assertThatThrownBy(
                        () ->
                                db.jdbc
                                        .sql(
                                                """
                INSERT INTO ingestion.document_outbox (id, tenant_id, document_id, document_version, event_type)
                VALUES (?, ?, ?, 999, 'UPLOAD_COMPLETED')
                """)
                                        .param(UUID.randomUUID())
                                        .param(UUID.randomUUID())
                                        .param(id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
