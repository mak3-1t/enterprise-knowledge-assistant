package com.eka.ingestion.internal.web;

import com.eka.ingestion.internal.persistence.UploadConflict;
import com.eka.ingestion.internal.service.DocumentUploadService;
import com.eka.ingestion.internal.storage.StorageUnavailable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
@ConditionalOnProperty(name = "eka.upload.enabled", havingValue = "true")
public class DocumentController {
    private final DocumentUploadService documents;

    public DocumentController(DocumentUploadService documents) {
        this.documents = documents;
    }

    @PostMapping("/uploads")
    public ResponseEntity<?> initiate(
            @RequestHeader("Idempotency-Key") String key, @RequestBody InitRequest request) {
        return response(
                200,
                documents.initiate(key, request.filename(), request.sizeBytes(), request.sha256()));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID id) {
        return response(200, documents.complete(id));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(200, documents.list(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id) {
        return response(200, documents.detail(id));
    }

    @GetMapping("/{id}/access")
    public ResponseEntity<?> access(@PathVariable UUID id) {
        return response(200, documents.readableRoles(id));
    }

    @PutMapping("/{id}/access")
    public ResponseEntity<?> access(@PathVariable UUID id, @RequestBody AccessRequest request) {
        return response(200, documents.setReadableRoles(id, request.readableBy()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        documents.delete(id);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable UUID id) {
        return response(200, documents.restore(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        var file = documents.download(id);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .contentLength(file.bytes())
                .body(new InputStreamResource(file.stream()));
    }

    @ExceptionHandler(UploadConflict.class)
    ResponseEntity<?> conflict(UploadConflict error) {
        int status =
                Map.ofEntries(
                                Map.entry("FORBIDDEN", 403),
                                Map.entry("NOT_FOUND", 404),
                                Map.entry("FILE_TOO_LARGE", 413),
                                Map.entry("UNSUPPORTED_TYPE", 415),
                                Map.entry("IDEMPOTENCY_CONFLICT", 409),
                                Map.entry("QUOTA_EXCEEDED", 409),
                                Map.entry("INVALID_STATE", 409),
                                Map.entry("UPLOAD_EXPIRED", 409),
                                Map.entry("RESTORE_UNAVAILABLE", 409),
                                Map.entry("DOCUMENT_NOT_READY", 409),
                                Map.entry("INVALID_ACCESS_ROLES", 400))
                        .getOrDefault(error.getMessage(), 400);
        return response(status, Map.of("error", error.getMessage()));
    }

    @ExceptionHandler({StorageUnavailable.class, DataAccessException.class})
    ResponseEntity<?> unavailable() {
        return response(503, Map.of("error", "UPLOAD_UNAVAILABLE"));
    }

    private static ResponseEntity<?> response(int status, Object body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }

    public record InitRequest(String filename, long sizeBytes, String sha256) {}

    public record AccessRequest(java.util.List<String> readableBy) {}
}
