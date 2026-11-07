package com.eka.ingestion.internal.scan;

import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.core.ResponseInputStream;

public final class InspectionSnapshot implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "scan-download-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Path file;

    private InspectionSnapshot(Path file) {
        this.file = file;
    }

    public static InspectionSnapshot create(UploadStorage storage, UploadRecord row) {
        Path file = null;
        try {
            file = Files.createTempFile("eka-scan-", ".quarantine");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input = storage.open(row);
                    var output = Files.newOutputStream(file)) {
                var expired = new AtomicBoolean();
                var deadline =
                        DEADLINES.schedule(
                                () -> {
                                    expired.set(true);
                                    abort(input);
                                },
                                60,
                                TimeUnit.SECONDS);
                try {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        size += read;
                        if (size > row.declaredSize() || size > 20971520) {
                            throw new InspectionRejected("CORRUPTED_FILE");
                        }
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                    if (expired.get()) {
                        throw new ScanUnavailable("PROCESSING_TIMEOUT");
                    }
                } finally {
                    deadline.cancel(false);
                }
            }
            if (size != row.declaredSize()
                    || !HexFormat.of().formatHex(digest.digest()).equals(row.sha256())) {
                throw new InspectionRejected("CORRUPTED_FILE");
            }
            return new InspectionSnapshot(file);
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            remove(file);
            throw new ScanUnavailable("SCAN_INPUT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            remove(file);
            throw failure;
        }
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() {
        remove(file);
    }

    private static void abort(InputStream input) {
        if (input instanceof ResponseInputStream<?> response) {
            response.abort();
        } else {
            try {
                input.close();
            } catch (IOException ignored) {
                // Timeout is a retry, never a clean verdict.
            }
        }
    }

    private static void remove(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException failed) {
                System.getLogger(InspectionSnapshot.class.getName())
                        .log(System.Logger.Level.WARNING, "Scan temporary file cleanup deferred");
            }
        }
    }
}
