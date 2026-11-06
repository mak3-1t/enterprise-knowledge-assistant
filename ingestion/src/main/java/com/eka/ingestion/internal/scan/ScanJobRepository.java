package com.eka.ingestion.internal.scan;

import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScanJobRepository {
    private final JdbcClient jdbc;
    private final UploadRepository uploads;

    public ScanJobRepository(JdbcClient jdbc, UploadRepository uploads) {
        this.jdbc = jdbc;
        this.uploads = uploads;
    }

    /** Local durable handoff only; published_at remains for the future Kafka publisher. */
    @Transactional
    public int dispatch() {
        var events =
                jdbc.sql(
                                """
                SELECT id, tenant_id, document_id, document_version, event_type
                FROM ingestion.document_outbox WHERE scan_dispatched_at IS NULL
                    AND event_type IN ('UPLOAD_COMPLETED','DOCUMENT_RESTORED','DOCUMENT_DELETED')
                ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED
                """)
                        .query()
                        .listOfRows();
        for (var event : events) {
            UUID id = (UUID) event.get("id");
            UUID tenant = (UUID) event.get("tenant_id");
            UUID document = (UUID) event.get("document_id");
            long generation = ((Number) event.get("document_version")).longValue();
            if (event.get("event_type").equals("DOCUMENT_DELETED")) {
                jdbc.sql(
                                """
                        UPDATE ingestion.document_scan_jobs SET state = 'CANCELLED',
                            lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND document_id = ? AND generation < ?
                            AND state IN ('PENDING','RUNNING','RETRY')
                        """)
                        .param(tenant)
                        .param(document)
                        .param(generation)
                        .update();
            } else {
                jdbc.sql(
                                """
                        INSERT INTO ingestion.document_scan_jobs (id, tenant_id, document_id, generation)
                        SELECT ?, tenant_id, id, version FROM ingestion.documents
                        WHERE tenant_id = ? AND id = ? AND version = ? AND upload_status = 'UPLOADED'
                        ON CONFLICT DO NOTHING
                        """)
                        .param(id)
                        .param(tenant)
                        .param(document)
                        .param(generation)
                        .update();
            }
            jdbc.sql(
                            "UPDATE ingestion.document_outbox SET scan_dispatched_at = CURRENT_TIMESTAMP WHERE id = ?")
                    .param(id)
                    .update();
        }
        return events.size();
    }

    @Transactional
    public Optional<ScanJob> claim() {
        var candidate =
                jdbc.sql(
                                """
                SELECT * FROM ingestion.document_scan_jobs
                WHERE (state IN ('PENDING','RETRY') AND available_at <= clock_timestamp())
                    OR (state = 'RUNNING' AND lease_until <= clock_timestamp())
                ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                        .query(ScanJobRepository::map)
                        .optional();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        var job = candidate.get();
        var row = lockDocument(job);
        if (row.isEmpty() || !matches(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return Optional.empty();
        }
        var document = row.get();
        if (job.attempts() >= 4) {
            uploads.fail(
                    job.tenantId(),
                    job.documentId(),
                    document.version(),
                    false,
                    "SCAN_RETRY_EXHAUSTED");
            terminal(job.id(), "DEAD", "SCAN_RETRY_EXHAUSTED");
            return Optional.empty();
        }
        long scanVersion = document.version();
        if (document.status() == UploadStatus.UPLOADED) {
            if (!uploads.advance(
                    job.tenantId(),
                    job.documentId(),
                    document.version(),
                    UploadStatus.UPLOADED,
                    UploadStatus.SCANNING)) {
                throw new IllegalStateException("Scan transition lost inside document lock");
            }
            scanVersion++;
        }
        return Optional.of(
                jdbc.sql(
                                """
                UPDATE ingestion.document_scan_jobs SET state = 'RUNNING', attempts = attempts + 1,
                    scan_version = ?, lease_token = ?, lease_until = clock_timestamp() + INTERVAL '5 minutes',
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? RETURNING *
                """)
                        .param(scanVersion)
                        .param(UUID.randomUUID())
                        .param(job.id())
                        .query(ScanJobRepository::map)
                        .single());
    }

    @Transactional
    public boolean cancel(ScanJob job) {
        if (!owns(job)) {
            return false;
        }
        terminal(job.id(), "CANCELLED", null);
        return true;
    }

    @Transactional
    public boolean clean(ScanJob job) {
        if (!owns(job)) {
            return false;
        }
        var row = lockDocument(job);
        if (row.isEmpty() || !matchesRunning(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return false;
        }
        if (!uploads.advance(
                job.tenantId(),
                job.documentId(),
                row.get().version(),
                UploadStatus.SCANNING,
                UploadStatus.PROCESSING)) {
            throw new IllegalStateException("Scan transition lost inside document lock");
        }
        jdbc.sql(
                        """
                INSERT INTO ingestion.document_outbox (id, tenant_id, document_id, document_version, event_type)
                VALUES (?, ?, ?, ?, 'CONTENT_VERIFIED')
                """)
                .param(UUID.randomUUID())
                .param(job.tenantId())
                .param(job.documentId())
                .param(row.get().version() + 1)
                .update();
        terminal(job.id(), "SUCCEEDED", null);
        return true;
    }

    @Transactional
    public boolean rejected(ScanJob job, String code) {
        safeCode(code);
        if (!owns(job)) {
            return false;
        }
        var row = lockDocument(job);
        if (row.isEmpty() || !matchesRunning(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return false;
        }
        uploads.fail(job.tenantId(), job.documentId(), row.get().version(), true, code);
        terminal(job.id(), "REJECTED", code);
        return true;
    }

    @Transactional
    public boolean retry(ScanJob job, String code) {
        safeCode(code);
        if (!owns(job)) {
            return false;
        }
        var row = lockDocument(job);
        if (row.isEmpty() || !matchesRunning(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return false;
        }
        if (job.attempts() >= 4) {
            uploads.fail(job.tenantId(), job.documentId(), row.get().version(), false, code);
            terminal(job.id(), "DEAD", code);
        } else {
            int delaySeconds = 5 * (1 << (job.attempts() - 1));
            jdbc.sql(
                            """
                    UPDATE ingestion.document_scan_jobs SET state = 'RETRY', error_code = ?,
                        available_at = clock_timestamp() + (? * INTERVAL '1 second'),
                        lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """)
                    .param(code)
                    .param(delaySeconds)
                    .param(job.id())
                    .update();
        }
        return true;
    }

    private boolean owns(ScanJob job) {
        return jdbc.sql(
                        """
                SELECT id FROM ingestion.document_scan_jobs WHERE id = ? AND tenant_id = ?
                    AND document_id = ? AND state = 'RUNNING' AND lease_token = ?
                    AND lease_until > clock_timestamp() FOR UPDATE
                """)
                .param(job.id())
                .param(job.tenantId())
                .param(job.documentId())
                .param(job.leaseToken())
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private Optional<UploadRecord> lockDocument(ScanJob job) {
        var id =
                jdbc.sql(
                                "SELECT id FROM ingestion.documents WHERE tenant_id = ? AND id = ? FOR UPDATE")
                        .param(job.tenantId())
                        .param(job.documentId())
                        .query(UUID.class)
                        .optional();
        return id.isEmpty() ? Optional.empty() : uploads.find(job.tenantId(), job.documentId());
    }

    private static boolean matches(ScanJob job, UploadRecord row) {
        return (row.status() == UploadStatus.UPLOADED && row.version() == job.generation())
                || matchesRunning(job, row);
    }

    private static boolean matchesRunning(ScanJob job, UploadRecord row) {
        return job.scanVersion() != null
                && row.status() == UploadStatus.SCANNING
                && row.version() == job.scanVersion();
    }

    private void terminal(UUID id, String state, String code) {
        jdbc.sql(
                        """
                UPDATE ingestion.document_scan_jobs SET state = ?, error_code = ?, lease_token = NULL,
                    lease_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """)
                .param(state)
                .param(code)
                .param(id)
                .update();
    }

    private static void safeCode(String code) {
        if (code == null || !code.matches("[A-Z_]{1,40}")) {
            throw new IllegalArgumentException("Invalid scan error code");
        }
    }

    private static ScanJob map(ResultSet row, int ignored) throws SQLException {
        return new ScanJob(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getLong("generation"),
                row.getObject("scan_version", Long.class),
                row.getInt("attempts"),
                row.getObject("lease_token", UUID.class));
    }
}
