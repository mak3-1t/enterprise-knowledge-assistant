package com.eka.ingestion.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** DB-only transactions. Storage I/O must finish before completion or purge acknowledgement. */
@Repository
public class UploadRepository {
    private final JdbcClient jdbc;

    public UploadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UploadRecord initiate(
            UUID tenant,
            UUID user,
            String key,
            String name,
            DocumentFormat format,
            long bytes,
            String requestedHash) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(user);
        Objects.requireNonNull(format);
        validateName(name);
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new UploadConflict("INVALID_IDEMPOTENCY_KEY");
        }
        if (bytes < 1 || bytes > DocumentRepository.MAX_FILE_BYTES) {
            throw new UploadConflict("FILE_TOO_LARGE");
        }
        if (requestedHash != null && !requestedHash.matches("[0-9a-f]{64}")) {
            throw new UploadConflict("INVALID_CHECKSUM");
        }
        String fingerprint = fingerprint(name, format, bytes, requestedHash);
        jdbc.sql(
                        "INSERT INTO ingestion.upload_quotas (tenant_id, uploaded_by) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .param(tenant)
                .param(user)
                .update();
        jdbc.sql(
                        "SELECT reserved_bytes FROM ingestion.upload_quotas WHERE tenant_id = ? AND uploaded_by = ? FOR UPDATE")
                .param(tenant)
                .param(user)
                .query(Long.class)
                .single();
        var existing =
                jdbc.sql(
                                "SELECT * FROM ingestion.documents WHERE tenant_id = ? AND uploaded_by = ? AND idempotency_key = ?")
                        .param(tenant)
                        .param(user)
                        .param(key)
                        .query(UploadRepository::map)
                        .optional();
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw new UploadConflict("IDEMPOTENCY_CONFLICT");
            }
            return existing.get();
        }
        int reserved =
                jdbc.sql(
                                """
                UPDATE ingestion.upload_quotas SET reserved_bytes = reserved_bytes + ?,
                    reserved_files = reserved_files + 1
                WHERE tenant_id = ? AND uploaded_by = ?
                    AND reserved_bytes + ? <= 524288000 AND reserved_files < 100
                """)
                        .param(bytes)
                        .param(tenant)
                        .param(user)
                        .param(bytes)
                        .update();
        if (reserved != 1) {
            throw new UploadConflict("QUOTA_EXCEEDED");
        }
        UUID id = UUID.randomUUID();
        return jdbc.sql(
                        """
                INSERT INTO ingestion.documents
                    (id, tenant_id, uploaded_by, filename, format, object_key, declared_size,
                     requested_sha256, idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """)
                .param(id)
                .param(tenant)
                .param(user)
                .param(name)
                .param(format.name())
                .param("tenants/" + tenant + "/documents/" + id + "/original")
                .param(bytes)
                .param(requestedHash)
                .param(key)
                .param(fingerprint)
                .query(UploadRepository::map)
                .single();
    }

    public Optional<UploadRecord> find(UUID tenant, UUID id) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(id);
        return jdbc.sql(
                        "SELECT * FROM ingestion.documents WHERE tenant_id = ? AND id = ? AND idempotency_key IS NOT NULL")
                .param(tenant)
                .param(id)
                .query(UploadRepository::map)
                .optional();
    }

    public List<UploadRecord> list(UUID tenant, int limit, int offset) {
        Objects.requireNonNull(tenant);
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new UploadConflict("INVALID_PAGE");
        }
        return jdbc.sql(
                        """
                SELECT * FROM ingestion.documents WHERE tenant_id = ? AND idempotency_key IS NOT NULL
                    AND upload_status NOT IN ('DELETED','EXPIRED')
                ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """)
                .param(tenant)
                .param(limit)
                .param(offset)
                .query(UploadRepository::map)
                .list();
    }

    @Transactional
    public UploadRecord sealAndComplete(
            UUID tenant, UUID user, UUID id, Supplier<VerifiedUpload> seal) {
        var row = lock(tenant, id);
        if (!row.uploadedBy().equals(user)) {
            throw new UploadConflict("NOT_FOUND");
        }
        if (List.of(
                        UploadStatus.UPLOADED,
                        UploadStatus.SCANNING,
                        UploadStatus.PROCESSING,
                        UploadStatus.READY)
                .contains(row.status())) {
            return row;
        }
        if (row.status() != UploadStatus.PENDING) {
            throw new UploadConflict("INVALID_STATE");
        }
        boolean unexpired =
                jdbc.sql(
                                "SELECT expires_at > CURRENT_TIMESTAMP FROM ingestion.documents WHERE tenant_id = ? AND id = ?")
                        .param(tenant)
                        .param(id)
                        .query(Boolean.class)
                        .single();
        if (!unexpired) {
            throw new UploadConflict("UPLOAD_EXPIRED");
        }
        var verified = seal.get();
        return complete(tenant, user, id, verified.bytes(), verified.sha256());
    }

    @Transactional
    public UploadRecord complete(UUID tenant, UUID user, UUID id, long bytes, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new UploadConflict("INVALID_CHECKSUM");
        }
        var row = lock(tenant, id);
        if (!row.uploadedBy().equals(user)) {
            throw new UploadConflict("NOT_FOUND");
        }
        if (row.status() != UploadStatus.PENDING) {
            if (List.of(
                                    UploadStatus.UPLOADED,
                                    UploadStatus.SCANNING,
                                    UploadStatus.PROCESSING,
                                    UploadStatus.READY)
                            .contains(row.status())
                    && Objects.equals(row.byteSize(), bytes)
                    && hash.equals(row.sha256())) {
                return row;
            }
            throw new UploadConflict("INVALID_STATE");
        }
        if (bytes != row.declaredSize()
                || (row.requestedSha256() != null && !row.requestedSha256().equals(hash))) {
            throw new UploadConflict("CHECKSUM_OR_SIZE_MISMATCH");
        }
        int updated =
                jdbc.sql(
                                """
                UPDATE ingestion.documents SET storage_state = 'STORED', ingestion_status = 'UPLOADED',
                    upload_status = 'UPLOADED', byte_size = ?, sha256 = ?, uploaded_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND upload_status = 'PENDING'
                    AND expires_at > clock_timestamp()
                """)
                        .param(bytes)
                        .param(hash)
                        .param(tenant)
                        .param(id)
                        .update();
        if (updated != 1) {
            throw new UploadConflict("UPLOAD_EXPIRED");
        }
        event(tenant, id, row.version() + 1, "UPLOAD_COMPLETED");
        return find(tenant, id).orElseThrow();
    }

    @Transactional
    public void delete(UUID tenant, UUID user, boolean admin, UUID id) {
        var row = lock(tenant, id);
        requireOwner(row, user, admin);
        if (row.status() == UploadStatus.DELETED) {
            return;
        }
        jdbc.sql(
                        """
                UPDATE ingestion.documents SET restore_status = upload_status, upload_status = 'DELETED',
                    source_generation = NULL, deleted_at = CURRENT_TIMESTAMP, cleanup_after = CASE WHEN
                        upload_status IN ('UPLOADED','SCANNING','PROCESSING','READY')
                        THEN CURRENT_TIMESTAMP + INTERVAL '7 days'
                        ELSE GREATEST(expires_at, CURRENT_TIMESTAMP) END,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ?
                """)
                .param(tenant)
                .param(id)
                .update();
        event(tenant, id, row.version() + 1, "DOCUMENT_DELETED");
        jdbc.sql(
                        "DELETE FROM ingestion.document_source_sections WHERE tenant_id = ? AND document_id = ?")
                .param(tenant)
                .param(id)
                .update();
    }

    @Transactional
    public UploadRecord restore(UUID tenant, UUID user, boolean admin, UUID id) {
        var row = lock(tenant, id);
        requireOwner(row, user, admin);
        int updated =
                jdbc.sql(
                                """
                UPDATE ingestion.documents SET upload_status = 'UPLOADED', ingestion_status = 'UPLOADED',
                    deleted_at = NULL, restore_status = NULL, cleanup_after = NULL,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND upload_status = 'DELETED' AND purged_at IS NULL
                    AND cleanup_after > CURRENT_TIMESTAMP
                    AND restore_status IN ('UPLOADED','SCANNING','PROCESSING','READY')
                """)
                        .param(tenant)
                        .param(id)
                        .update();
        if (updated != 1) {
            throw new UploadConflict("RESTORE_UNAVAILABLE");
        }
        event(tenant, id, row.version() + 1, "DOCUMENT_RESTORED");
        return find(tenant, id).orElseThrow();
    }

    /** Worker CAS: stale delivery cannot publish or resurrect a deleted document. */
    public boolean advance(UUID tenant, UUID id, long version, UploadStatus from, UploadStatus to) {
        boolean allowed =
                (from == UploadStatus.UPLOADED && to == UploadStatus.SCANNING)
                        || (from == UploadStatus.SCANNING && to == UploadStatus.PROCESSING)
                        || (from == UploadStatus.PROCESSING && to == UploadStatus.READY);
        if (!allowed) {
            throw new UploadConflict("INVALID_TRANSITION");
        }
        return jdbc.sql(
                                """
                UPDATE ingestion.documents SET upload_status = ?, version = version + 1,
                    ingestion_status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND id = ? AND version = ? AND upload_status = ?
                    AND idempotency_key IS NOT NULL
                """)
                        .param(to.name())
                        .param(to == UploadStatus.READY ? "READY" : "PROCESSING")
                        .param(tenant)
                        .param(id)
                        .param(version)
                        .param(from.name())
                        .update()
                == 1;
    }

    /** Retain verified originals briefly so an operator can restart failed processing. */
    public boolean failProcessingForRecovery(UUID tenant, UUID id, long version, String code) {
        return fail(tenant, id, version, false, code, true);
    }

    public boolean fail(UUID tenant, UUID id, long version, boolean rejected, String code) {
        return fail(tenant, id, version, rejected, code, false);
    }

    private boolean fail(
            UUID tenant, UUID id, long version, boolean rejected, String code, boolean retain) {
        if (code == null || !code.matches("[A-Z_]{1,40}")) {
            throw new UploadConflict("INVALID_ERROR_CODE");
        }
        return jdbc.sql(
                                """
                UPDATE ingestion.documents SET upload_status = ?, error_code = ?,
                    cleanup_after = CASE WHEN ? THEN CURRENT_TIMESTAMP + INTERVAL '7 days' ELSE CURRENT_TIMESTAMP END,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ?
                    AND upload_status IN ('UPLOADED','SCANNING','PROCESSING')
                    AND (NOT ? OR upload_status = 'PROCESSING')
                    AND idempotency_key IS NOT NULL
                """)
                        .param(rejected ? "REJECTED" : "FAILED")
                        .param(code)
                        .param(retain)
                        .param(tenant)
                        .param(id)
                        .param(version)
                        .param(retain)
                        .update()
                == 1;
    }

    public int expirePending() {
        return jdbc.sql(
                        """
                UPDATE ingestion.documents SET upload_status = 'EXPIRED', cleanup_after = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE upload_status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
                    AND idempotency_key IS NOT NULL
                """)
                .update();
    }

    /** Internal maintenance inventory, never exposed over HTTP. */
    public List<UploadRecord> cleanupCandidates(int limit) {
        if (limit < 1 || limit > 100) {
            throw new UploadConflict("INVALID_PAGE");
        }
        return jdbc.sql(
                        """
                SELECT * FROM ingestion.documents WHERE cleanup_after <= CURRENT_TIMESTAMP
                    AND purged_at IS NULL AND idempotency_key IS NOT NULL
                ORDER BY cleanup_after, id LIMIT ?
                """)
                .param(limit)
                .query(UploadRepository::map)
                .list();
    }

    /** Run object deletion inside the row lock so restore cannot race a physical purge. */
    @Transactional
    public boolean purge(UUID tenant, UUID id, Runnable removeObjects) {
        var row = lock(tenant, id);
        if (row.purgedAt() != null) {
            return false;
        }
        boolean eligible =
                jdbc.sql(
                                        """
                SELECT count(*) FROM ingestion.documents WHERE tenant_id = ? AND id = ?
                    AND cleanup_after <= CURRENT_TIMESTAMP
                    AND upload_status IN ('EXPIRED','REJECTED','FAILED','DELETED')
                """)
                                .param(tenant)
                                .param(id)
                                .query(Long.class)
                                .single()
                        == 1;
        if (!eligible) {
            return false;
        }
        removeObjects.run();
        jdbc.sql(
                        "DELETE FROM ingestion.document_source_sections WHERE tenant_id = ? AND document_id = ?")
                .param(tenant)
                .param(id)
                .update();
        jdbc.sql(
                        "UPDATE ingestion.documents SET purged_at = CURRENT_TIMESTAMP, source_generation = NULL, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?")
                .param(tenant)
                .param(id)
                .update();
        jdbc.sql(
                        """
                UPDATE ingestion.upload_quotas SET reserved_bytes = reserved_bytes - ?,
                    reserved_files = reserved_files - 1 WHERE tenant_id = ? AND uploaded_by = ?
                """)
                .param(row.declaredSize())
                .param(tenant)
                .param(row.uploadedBy())
                .update();
        return true;
    }

    private UploadRecord lock(UUID tenant, UUID id) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(id);
        return jdbc.sql(
                        "SELECT * FROM ingestion.documents WHERE tenant_id = ? AND id = ? AND idempotency_key IS NOT NULL FOR UPDATE")
                .param(tenant)
                .param(id)
                .query(UploadRepository::map)
                .optional()
                .orElseThrow(() -> new UploadConflict("NOT_FOUND"));
    }

    private void event(UUID tenant, UUID id, long version, String type) {
        jdbc.sql(
                        """
                INSERT INTO ingestion.document_outbox (id, tenant_id, document_id, document_version, event_type)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(UUID.randomUUID())
                .param(tenant)
                .param(id)
                .param(version)
                .param(type)
                .update();
    }

    private static void requireOwner(UploadRecord row, UUID user, boolean admin) {
        if (!admin && !row.uploadedBy().equals(user)) {
            throw new UploadConflict("NOT_FOUND");
        }
    }

    private static void validateName(String name) {
        if (name == null
                || name.isBlank()
                || name.length() > 255
                || !name.equals(name.strip())
                || name.equals(".")
                || name.equals("..")
                || name.contains("/")
                || name.contains("\\")
                || name.contains(":")
                || name.codePoints()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT)) {
            throw new UploadConflict("INVALID_FILENAME");
        }
    }

    private static String fingerprint(String name, DocumentFormat format, long size, String hash) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (name
                                                            + "\0"
                                                            + format
                                                            + "\0"
                                                            + size
                                                            + "\0"
                                                            + Objects.toString(hash, ""))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static UploadRecord map(ResultSet rs, int row) throws SQLException {
        return new UploadRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("uploaded_by", UUID.class),
                rs.getString("filename"),
                DocumentFormat.valueOf(rs.getString("format")),
                rs.getString("object_key"),
                rs.getLong("declared_size"),
                rs.getString("requested_sha256"),
                rs.getString("request_fingerprint"),
                UploadStatus.valueOf(rs.getString("upload_status")),
                rs.getLong("version"),
                instant(rs, "expires_at"),
                instant(rs, "deleted_at"),
                instant(rs, "purged_at"),
                rs.getObject("byte_size", Long.class),
                rs.getString("sha256"),
                rs.getString("error_code"));
    }
}
