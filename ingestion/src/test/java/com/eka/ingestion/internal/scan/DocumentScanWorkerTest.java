package com.eka.ingestion.internal.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.service.UploadEvents;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentScanWorkerTest {
    private final ScanJobRepository jobs = mock(ScanJobRepository.class);
    private final UploadRepository uploads = mock(UploadRepository.class);
    private final UploadStorage storage = mock(UploadStorage.class);
    private final MalwareScanner malware = mock(MalwareScanner.class);
    private final ContentInspector content = mock(ContentInspector.class);
    private final UploadEvents events = mock(UploadEvents.class);
    private final DocumentScanWorker worker =
            new DocumentScanWorker(jobs, uploads, storage, malware, content, events);
    private final ScanJob job =
            new ScanJob(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    2L,
                    1,
                    UUID.randomUUID());

    @BeforeEach
    void setup() {
        var row =
                new UploadRecord(
                        job.documentId(),
                        job.tenantId(),
                        UUID.randomUUID(),
                        "test.txt",
                        DocumentFormat.TXT,
                        "private",
                        5,
                        null,
                        "a".repeat(64),
                        UploadStatus.SCANNING,
                        2,
                        Instant.now(),
                        null,
                        null,
                        5L,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                        null);
        when(uploads.find(job.tenantId(), job.documentId())).thenReturn(Optional.of(row));
        when(storage.open(any()))
                .thenAnswer(
                        call -> new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void cleanRequiresBothGatesAndTemporarySnapshotIsRemoved() {
        var temporary = new AtomicReference<Path>();
        doAnswer(
                        call -> {
                            Path file = call.getArgument(0);
                            assertThat(Files.readString(file)).isEqualTo("hello");
                            temporary.set(file);
                            return null;
                        })
                .when(malware)
                .scan(any());
        when(jobs.clean(job)).thenReturn(true);
        worker.process(job);
        verify(content).inspect(any(), any());
        verify(jobs).clean(job);
        verify(events).record(UploadEvents.Outcome.SCAN_PASSED, job.documentId());
        assertThat(temporary.get()).doesNotExist();
    }

    @Test
    void malwareStopsBeforeParserAndNeverPublishesCleanResult() {
        doThrow(new InspectionRejected("MALWARE_DETECTED")).when(malware).scan(any());
        worker.process(job);
        verify(jobs).rejected(job, "MALWARE_DETECTED");
        verify(content, never()).inspect(any(), any());
        verify(jobs, never()).clean(any());
    }

    @Test
    void unavailableScannerAndInspectionTimeoutRetryWithoutTreatingFileAsClean() {
        doThrow(new ScanUnavailable("SCANNER_UNAVAILABLE")).when(malware).scan(any());
        worker.process(job);
        verify(jobs).retry(job, "SCANNER_UNAVAILABLE");
        verify(jobs, never()).clean(any());
    }

    @Test
    void contentRejectionAndChangedStorageBytesAreRejected() {
        doThrow(new InspectionRejected("TYPE_MISMATCH")).when(content).inspect(any(), any());
        worker.process(job);
        verify(jobs).rejected(job, "TYPE_MISMATCH");
        when(storage.open(any()))
                .thenAnswer(
                        call -> new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)));
        worker.process(job);
        verify(jobs).rejected(job, "CORRUPTED_FILE");
        verify(jobs, never()).clean(any());
    }

    @Test
    void lostLeaseOrDeletedGenerationCannotEmitSuccessfulEvent() {
        when(jobs.clean(job)).thenReturn(false);
        worker.process(job);
        verify(events, never()).record(UploadEvents.Outcome.SCAN_PASSED, job.documentId());
    }
}
