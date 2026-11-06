package com.eka.ingestion.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All caller operations require tenant scope. Authorization belongs to the future service boundary.
 */
@Repository
public class DocumentRepository {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DocumentRecord createUpload(
            UUID tenant, UUID id, UUID uploader, String filename, DocumentFormat format) {
        scope(tenant, id);
        Objects.requireNonNull(uploader);
        Objects.requireNonNull(format);
        if (filename == null
                || filename.isBlank()
                || filename.length() > 255
                || !filename.equals(filename.strip())
                || filename.equals(".")
                || filename.equals("..")
                || filename.contains("/")
                || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid filename");
        }
        String key = "tenants/" + tenant + "/documents/" + id + "/original";
        return jdbc.sql(
                        """
                INSERT INTO ingestion.documents (id, tenant_id, uploaded_by, filename, format, object_key)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING *
                """)
                .param(id)
                .param(tenant)
                .param(uploader)
                .param(filename)
                .param(format.name())
                .param(key)
                .query(DocumentRepository::map)
                .single();
    }

    public boolean completeUpload(
            UUID tenant, UUID id, long expectedVersion, long actualBytes, String checksum) {
        scope(tenant, id);
        if (expectedVersion < 0
                || actualBytes < 1
                || actualBytes > MAX_FILE_BYTES
                || checksum == null
                || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid upload completion");
        }
        return jdbc.sql(
                                """
                UPDATE ingestion.documents SET storage_state = 'STORED', ingestion_status = 'UPLOADED',
                    byte_size = ?, sha256 = ?, uploaded_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND storage_state = 'UPLOADING' AND version = ?
                    AND idempotency_key IS NULL
                """)
                        .param(actualBytes)
                        .param(checksum)
                        .param(tenant)
                        .param(id)
                        .param(expectedVersion)
                        .update()
                == 1;
    }

    public boolean failUpload(UUID tenant, UUID id, long expectedVersion) {
        scope(tenant, id);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Invalid upload version");
        }
        return jdbc.sql(
                                """
                UPDATE ingestion.documents SET storage_state = 'FAILED', version = version + 1
                WHERE tenant_id = ? AND id = ? AND storage_state = 'UPLOADING' AND version = ?
                    AND idempotency_key IS NULL
                """)
                        .param(tenant)
                        .param(id)
                        .param(expectedVersion)
                        .update()
                == 1;
    }

    /** Includes hidden intents for ingestion-internal completion/cleanup only. */
    public Optional<DocumentRecord> findUpload(UUID tenant, UUID id) {
        scope(tenant, id);
        return jdbc.sql(
                        "SELECT * FROM ingestion.documents WHERE tenant_id = ? AND id = ? AND idempotency_key IS NULL")
                .param(tenant)
                .param(id)
                .query(DocumentRepository::map)
                .optional();
    }

    public Optional<DocumentRecord> findStored(UUID tenant, UUID id) {
        scope(tenant, id);
        return jdbc.sql(
                        "SELECT * FROM ingestion.documents WHERE tenant_id = ? AND id = ? AND storage_state = 'STORED' AND idempotency_key IS NULL")
                .param(tenant)
                .param(id)
                .query(DocumentRepository::map)
                .optional();
    }

    public List<DocumentRecord> listStored(UUID tenant, int limit, int offset) {
        Objects.requireNonNull(tenant);
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        return jdbc.sql(
                        """
                SELECT * FROM ingestion.documents WHERE tenant_id = ? AND storage_state = 'STORED' AND idempotency_key IS NULL
                ORDER BY id LIMIT ? OFFSET ?
                """)
                .param(tenant)
                .param(limit)
                .param(offset)
                .query(DocumentRepository::map)
                .list();
    }

    private static void scope(UUID tenant, UUID id) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(id);
    }

    private static DocumentRecord map(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("ingestion_status");
        Timestamp uploaded = rs.getTimestamp("uploaded_at");
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("uploaded_by", UUID.class),
                rs.getString("filename"),
                DocumentFormat.valueOf(rs.getString("format")),
                rs.getString("object_key"),
                StorageState.valueOf(rs.getString("storage_state")),
                status == null ? null : IngestionStatus.valueOf(status),
                rs.getObject("byte_size", Long.class),
                rs.getString("sha256"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                uploaded == null ? null : uploaded.toInstant());
    }
}
