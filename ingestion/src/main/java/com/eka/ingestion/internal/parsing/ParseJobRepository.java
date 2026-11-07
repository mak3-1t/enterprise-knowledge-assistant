package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ParseJobRepository {
    private final JdbcClient jdbc;
    private final UploadRepository uploads;

    public ParseJobRepository(JdbcClient jdbc, UploadRepository uploads) {
        this.jdbc = jdbc;
        this.uploads = uploads;
    }

    /**
     * Returns only after the durable inbox transaction commits; Kafka offset follows that commit.
     */
    @Transactional
    public void accept(String topic, int partition, long offset, String key, String payload) {
        var event = ParseEvent.decode(key, payload);
        if (event == null
                || !Set.of(
                                "UPLOAD_COMPLETED",
                                "DOCUMENT_DELETED",
                                "DOCUMENT_RESTORED",
                                "CONTENT_VERIFIED",
                                "SOURCE_PARSED")
                        .contains(event.type())) {
            reject(topic, partition, offset, payload, "INVALID_EVENT");
            return;
        }
        if (!event.type().equals("CONTENT_VERIFIED")) {
            return;
        }
        var document =
                jdbc.sql(
                                """
                SELECT d.id FROM ingestion.documents d JOIN ingestion.document_outbox e
                    ON e.document_id = d.id AND e.tenant_id = d.tenant_id
                WHERE e.id = ? AND e.tenant_id = ? AND e.document_id = ? AND e.document_version = ?
                    AND e.event_type = 'CONTENT_VERIFIED' AND e.schema_version = 1 AND e.created_at = ?
                FOR UPDATE OF d
                """)
                        .param(event.id())
                        .param(event.tenant())
                        .param(event.document())
                        .param(event.version())
                        .param(event.occurredAt().atOffset(ZoneOffset.UTC))
                        .query(UUID.class)
                        .optional();
        if (document.isEmpty()) {
            reject(topic, partition, offset, payload, "UNVERIFIED_EVENT");
            return;
        }
        boolean exists =
                jdbc.sql("SELECT count(*) FROM ingestion.document_parse_jobs WHERE id = ?")
                                .param(event.id())
                                .query(Long.class)
                                .single()
                        == 1;
        if (exists) {
            return;
        }
        var row = uploads.find(event.tenant(), event.document()).orElseThrow();
        if (row.status() != UploadStatus.PROCESSING || row.version() != event.version()) {
            reject(topic, partition, offset, payload, "STALE_EVENT");
            return;
        }
        jdbc.sql(
                        """
                INSERT INTO ingestion.document_parse_jobs (id, tenant_id, document_id, generation)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)
                .param(event.id())
                .param(event.tenant())
                .param(event.document())
                .param(event.version())
                .update();
    }

    @Transactional
    public Optional<ParseJob> claim() {
        var found =
                jdbc.sql(
                                """
                SELECT * FROM ingestion.document_parse_jobs
                WHERE (state IN ('PENDING','RETRY') AND available_at <= clock_timestamp())
                    OR (state = 'RUNNING' AND lease_until <= clock_timestamp())
                ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                        .query(ParseJobRepository::map)
                        .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var job = found.get();
        var row = lockDocument(job);
        if (row.isEmpty() || !current(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return Optional.empty();
        }
        if (job.attempts() >= 4) {
            uploads.fail(
                    job.tenantId(),
                    job.documentId(),
                    job.generation(),
                    false,
                    "PARSE_RETRY_EXHAUSTED");
            terminal(job.id(), "DEAD", "PARSE_RETRY_EXHAUSTED");
            return Optional.empty();
        }
        return Optional.of(
                jdbc.sql(
                                """
                UPDATE ingestion.document_parse_jobs SET state = 'RUNNING', attempts = attempts + 1,
                    lease_token = ?, lease_until = clock_timestamp() + INTERVAL '5 minutes'
                WHERE id = ? RETURNING *
                """)
                        .param(UUID.randomUUID())
                        .param(job.id())
                        .query(ParseJobRepository::map)
                        .single());
    }

    @Transactional
    public boolean complete(ParseJob job, ParsedDocument parsed) {
        if (!owns(job)) {
            return false;
        }
        var row = lockDocument(job);
        if (row.isEmpty() || !current(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return false;
        }
        validate(parsed, row.get());
        int ordinal = 0;
        for (var section : parsed.sections()) {
            jdbc.sql(
                            """
                    INSERT INTO ingestion.document_source_sections
                        (tenant_id, document_id, generation, ordinal, page, source_text, parser_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)
                    .param(job.tenantId())
                    .param(job.documentId())
                    .param(job.generation())
                    .param(ordinal++)
                    .param(section.page())
                    .param(section.text())
                    .param(parsed.parserVersion())
                    .update();
        }
        if (!owns(job)) {
            throw new TextExtractionFailure("PARSE_UNAVAILABLE");
        }
        jdbc.sql(
                        """
                UPDATE ingestion.documents SET source_generation = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?
                """)
                .param(job.generation())
                .param(job.tenantId())
                .param(job.documentId())
                .update();
        jdbc.sql(
                        """
                INSERT INTO ingestion.document_outbox (id, tenant_id, document_id, document_version, event_type)
                VALUES (?, ?, ?, ?, 'SOURCE_PARSED')
                """)
                .param(UUID.randomUUID())
                .param(job.tenantId())
                .param(job.documentId())
                .param(job.generation() + 1)
                .update();
        terminal(job.id(), "SUCCEEDED", null);
        return true;
    }

    @Transactional
    public boolean failed(ParseJob job, String code, boolean permanent) {
        if (code == null || !code.matches("[A-Z_]{1,40}")) {
            throw new IllegalArgumentException("Invalid parser error code");
        }
        if (!owns(job)) {
            return false;
        }
        var row = lockDocument(job);
        if (row.isEmpty() || !current(job, row.get())) {
            terminal(job.id(), "CANCELLED", null);
            return false;
        }
        if (permanent || job.attempts() >= 4) {
            uploads.fail(job.tenantId(), job.documentId(), job.generation(), false, code);
            terminal(job.id(), "DEAD", code);
        } else {
            jdbc.sql(
                            """
                    UPDATE ingestion.document_parse_jobs SET state = 'RETRY', error_code = ?,
                        available_at = clock_timestamp() + (? * INTERVAL '1 second'),
                        lease_token = NULL, lease_until = NULL WHERE id = ?
                    """)
                    .param(code)
                    .param(5 * (1 << (job.attempts() - 1)))
                    .param(job.id())
                    .update();
        }
        return true;
    }

    /** Private, tenant-scoped source access for subsequent chunking; no public text route. */
    public List<ParsedDocument.Section> loadCurrent(UUID tenant, UUID document) {
        java.util.Objects.requireNonNull(tenant);
        java.util.Objects.requireNonNull(document);
        return jdbc.sql(
                        """
                SELECT s.page, s.source_text FROM ingestion.document_source_sections s
                JOIN ingestion.documents d ON d.tenant_id = s.tenant_id AND d.id = s.document_id
                    AND d.source_generation = s.generation
                WHERE s.tenant_id = ? AND s.document_id = ? AND d.upload_status IN ('PROCESSING','READY')
                ORDER BY s.ordinal
                """)
                .param(tenant)
                .param(document)
                .query(
                        (rs, ignored) ->
                                new ParsedDocument.Section(
                                        rs.getObject("page", Integer.class),
                                        rs.getString("source_text")))
                .list();
    }

    private boolean owns(ParseJob job) {
        return jdbc.sql(
                        """
                SELECT id FROM ingestion.document_parse_jobs WHERE id = ? AND tenant_id = ? AND document_id = ?
                    AND generation = ? AND state = 'RUNNING' AND lease_token = ?
                    AND lease_until > clock_timestamp() FOR UPDATE
                """)
                .param(job.id())
                .param(job.tenantId())
                .param(job.documentId())
                .param(job.generation())
                .param(job.leaseToken())
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private Optional<UploadRecord> lockDocument(ParseJob job) {
        var found =
                jdbc.sql(
                                "SELECT id FROM ingestion.documents WHERE tenant_id = ? AND id = ? FOR UPDATE")
                        .param(job.tenantId())
                        .param(job.documentId())
                        .query(UUID.class)
                        .optional();
        return found.isEmpty() ? Optional.empty() : uploads.find(job.tenantId(), job.documentId());
    }

    private static boolean current(ParseJob job, UploadRecord row) {
        return row.status() == UploadStatus.PROCESSING && row.version() == job.generation();
    }

    private void terminal(UUID id, String state, String code) {
        jdbc.sql(
                        "UPDATE ingestion.document_parse_jobs SET state = ?, error_code = ?, lease_token = NULL, lease_until = NULL WHERE id = ?")
                .param(state)
                .param(code)
                .param(id)
                .update();
    }

    private void reject(String topic, int partition, long offset, String payload, String reason) {
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    java.util.Objects.toString(payload, "")
                                                            .getBytes(StandardCharsets.UTF_8)));
            jdbc.sql(
                            """
                    INSERT INTO ingestion.document_intake_rejections (topic, partition_id, record_offset, payload_sha256, reason)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (topic, partition_id, record_offset) DO NOTHING
                    """)
                    .param(topic)
                    .param(partition)
                    .param(offset)
                    .param(hash)
                    .param(reason)
                    .update();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validate(ParsedDocument parsed, UploadRecord row) {
        if (!"apache-tika:3.3.3".equals(parsed.parserVersion())
                || parsed.sections().isEmpty()
                || parsed.sections().size() > 2000) {
            throw new TextExtractionFailure("EXTRACT_EMPTY");
        }
        long size = 0;
        int page = 0;
        for (var section : parsed.sections()) {
            if (section.text() == null || section.text().isBlank()) {
                throw new TextExtractionFailure("EXTRACT_EMPTY");
            }
            size += section.text().length();
            if (row.format() == com.eka.ingestion.internal.persistence.DocumentFormat.PDF) {
                if (section.page() == null || section.page() <= page || section.page() > 2000) {
                    throw new TextExtractionFailure("CORRUPTED_FILE");
                }
                page = section.page();
            } else if (section.page() != null) {
                throw new TextExtractionFailure("CORRUPTED_FILE");
            }
        }
        if (size > TikaTextExtractor.MAX_CHARACTERS) {
            throw new TextExtractionFailure("EXTRACT_LIMIT");
        }
    }

    private static ParseJob map(ResultSet row, int ignored) throws SQLException {
        return new ParseJob(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getLong("generation"),
                row.getInt("attempts"),
                row.getObject("lease_token", UUID.class));
    }
}
