package com.eka.ingestion.internal.service;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadConflict;
import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "eka.upload.enabled", havingValue = "true")
public class DocumentUploadService {
    private static final Set<String> PUBLIC_ERRORS =
            Set.of(
                    "MALWARE_DETECTED",
                    "TYPE_MISMATCH",
                    "CORRUPTED_FILE",
                    "PASSWORD_PROTECTED",
                    "MACRO_DETECTED",
                    "ACTIVE_CONTENT_UNSUPPORTED",
                    "FILE_TOO_LARGE",
                    "SCAN_LIMIT_EXCEEDED",
                    "SCAN_RETRY_EXHAUSTED",
                    "SCAN_UNAVAILABLE",
                    "SCANNER_UNAVAILABLE",
                    "SCAN_INPUT_UNAVAILABLE",
                    "INSPECTION_UNAVAILABLE",
                    "EXTRACT_EMPTY",
                    "EXTRACT_LIMIT",
                    "PARSE_UNAVAILABLE",
                    "PROCESSING_TIMEOUT",
                    "PARSE_RETRY_EXHAUSTED",
                    "EMBEDDING_CHUNK_LIMIT",
                    "EMBEDDING_INPUT_LIMIT",
                    "EMBEDDING_RETRY_EXHAUSTED",
                    "EMBEDDING_PROVIDER_UNAVAILABLE",
                    "EMBEDDING_UNAVAILABLE");

    private final CurrentAccount current;
    private final UploadRepository uploads;
    private final UploadStorage storage;
    private final UploadEvents events;

    public DocumentUploadService(
            CurrentAccount current,
            UploadRepository uploads,
            UploadStorage storage,
            UploadEvents events) {
        this.current = current;
        this.uploads = uploads;
        this.storage = storage;
        this.events = events;
    }

    public Intent initiate(String key, String filename, long bytes, String checksum) {
        var account = writer();
        if (filename == null || !filename.contains(".")) {
            throw new UploadConflict("UNSUPPORTED_TYPE");
        }
        DocumentFormat format;
        try {
            format =
                    DocumentFormat.valueOf(
                            filename.substring(filename.lastIndexOf('.') + 1)
                                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new UploadConflict("UNSUPPORTED_TYPE");
        }
        var row =
                uploads.initiate(
                        account.tenantId(),
                        account.userId(),
                        key,
                        filename,
                        format,
                        bytes,
                        checksum);
        var result =
                new Intent(
                        view(row),
                        row.status() == UploadStatus.PENDING ? storage.authorize(row) : null);
        events.record(UploadEvents.Outcome.INTENT_RETURNED, row.id());
        return result;
    }

    public View complete(UUID id) {
        var account = writer();
        var row = require(account.tenantId(), id);
        var completed =
                view(
                        uploads.sealAndComplete(
                                account.tenantId(), account.userId(), id, () -> storage.seal(row)));
        events.record(UploadEvents.Outcome.COMPLETION_RETURNED, id);
        return completed;
    }

    public List<View> list(int page, int size) {
        var account = current.require();
        if (page < 0 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size) {
            throw new UploadConflict("INVALID_PAGE");
        }
        return uploads.list(account.tenantId(), size, page * size).stream()
                .map(DocumentUploadService::view)
                .toList();
    }

    public View detail(UUID id) {
        return view(visible(current.require().tenantId(), id));
    }

    public void delete(UUID id) {
        var account = writer();
        uploads.delete(account.tenantId(), account.userId(), account.role().equals("ADMIN"), id);
        events.record(UploadEvents.Outcome.DELETED, id);
    }

    public View restore(UUID id) {
        var account = writer();
        var restored =
                view(
                        uploads.restore(
                                account.tenantId(),
                                account.userId(),
                                account.role().equals("ADMIN"),
                                id));
        events.record(UploadEvents.Outcome.RESTORED, id);
        return restored;
    }

    public Download download(UUID id) {
        var row = visible(current.require().tenantId(), id);
        if (row.status() != UploadStatus.READY) {
            throw new UploadConflict("DOCUMENT_NOT_READY");
        }
        return new Download(
                row.filename(), row.format().mediaType(), row.byteSize(), storage.open(row));
    }

    private AuthenticatedAccount writer() {
        var account = current.require();
        if (!List.of("ADMIN", "EDITOR").contains(account.role())) {
            throw new UploadConflict("FORBIDDEN");
        }
        return account;
    }

    private UploadRecord require(UUID tenant, UUID id) {
        return uploads.find(tenant, id).orElseThrow(() -> new UploadConflict("NOT_FOUND"));
    }

    private UploadRecord visible(UUID tenant, UUID id) {
        var row = require(tenant, id);
        if (row.status() == UploadStatus.DELETED || row.status() == UploadStatus.EXPIRED) {
            throw new UploadConflict("NOT_FOUND");
        }
        return row;
    }

    private static View view(UploadRecord row) {
        return new View(
                row.id(),
                row.filename(),
                row.format().name(),
                row.status(),
                row.declaredSize(),
                row.expiresAt(),
                row.version(),
                publicError(row));
    }

    private static String publicError(UploadRecord row) {
        if (row.status() != UploadStatus.FAILED && row.status() != UploadStatus.REJECTED) {
            return null;
        }
        String code = row.errorCode();
        return code != null && PUBLIC_ERRORS.contains(code) ? code : "PROCESSING_FAILED";
    }

    public record View(
            UUID id,
            String filename,
            String format,
            UploadStatus status,
            long sizeBytes,
            Instant expiresAt,
            long version,
            String errorCode) {}

    public record Intent(View document, UploadStorage.UploadForm upload) {}

    public record Download(String filename, String mediaType, long bytes, InputStream stream) {}
}
