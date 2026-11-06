package com.eka.ingestion.internal.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRelayRepository {
    private final JdbcClient jdbc;

    public OutboxRelayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<OutboxDelivery> claim() {
        var candidate =
                jdbc.sql(
                                """
                SELECT event.id FROM ingestion.document_outbox event
                WHERE event.published_at IS NULL AND event.relay_available_at <= clock_timestamp()
                    AND (event.relay_lease_until IS NULL OR event.relay_lease_until <= clock_timestamp())
                    AND NOT EXISTS (
                        SELECT 1 FROM ingestion.document_outbox prior
                        WHERE prior.document_id = event.document_id AND prior.published_at IS NULL
                            AND (prior.document_version, prior.id) < (event.document_version, event.id))
                ORDER BY event.created_at, event.id LIMIT 1 FOR UPDATE OF event SKIP LOCKED
                """)
                        .query(UUID.class)
                        .optional();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                jdbc.sql(
                                """
                UPDATE ingestion.document_outbox SET relay_lease_token = ?,
                    relay_lease_until = clock_timestamp() + INTERVAL '45 seconds',
                    relay_attempts = relay_attempts + 1
                WHERE id = ? RETURNING *
                """)
                        .param(UUID.randomUUID())
                        .param(candidate.get())
                        .query(OutboxRelayRepository::map)
                        .single());
    }

    public boolean acknowledge(OutboxDelivery delivery, OutboxDelivery.Receipt receipt) {
        return jdbc.sql(
                                """
                UPDATE ingestion.document_outbox SET published_at = clock_timestamp(),
                    broker_partition = ?, broker_offset = ?, relay_error_code = NULL,
                    relay_lease_token = NULL, relay_lease_until = NULL
                WHERE id = ? AND tenant_id = ? AND document_id = ? AND published_at IS NULL
                    AND relay_lease_token = ? AND relay_lease_until > clock_timestamp()
                """)
                        .param(receipt.partition())
                        .param(receipt.offset())
                        .param(delivery.id())
                        .param(delivery.tenantId())
                        .param(delivery.documentId())
                        .param(delivery.leaseToken())
                        .update()
                == 1;
    }

    public boolean defer(OutboxDelivery delivery) {
        int delay = delivery.attempts() >= 7 ? 60 : (1 << ((int) delivery.attempts() - 1));
        return jdbc.sql(
                                """
                UPDATE ingestion.document_outbox SET relay_error_code = 'BROKER_UNAVAILABLE',
                    relay_available_at = clock_timestamp() + (? * INTERVAL '1 second'),
                    relay_lease_token = NULL, relay_lease_until = NULL
                WHERE id = ? AND tenant_id = ? AND document_id = ? AND published_at IS NULL
                    AND relay_lease_token = ? AND relay_lease_until > clock_timestamp()
                """)
                        .param(delay)
                        .param(delivery.id())
                        .param(delivery.tenantId())
                        .param(delivery.documentId())
                        .param(delivery.leaseToken())
                        .update()
                == 1;
    }

    private static OutboxDelivery map(ResultSet row, int ignored) throws SQLException {
        return new OutboxDelivery(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getLong("document_version"),
                row.getString("event_type"),
                row.getTimestamp("created_at").toInstant(),
                row.getInt("schema_version"),
                row.getObject("relay_lease_token", UUID.class),
                row.getLong("relay_attempts"));
    }
}
