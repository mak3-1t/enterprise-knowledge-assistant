package com.eka.ingestion.internal.parsing;

import static org.mockito.ArgumentMatchers.any;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParseWorkerTest {
    private final ParseJobRepository jobs = mock(ParseJobRepository.class);
    private final UploadRepository uploads = mock(UploadRepository.class);
    private final UploadStorage storage = mock(UploadStorage.class);
    private final TextExtractor extractor = mock(TextExtractor.class);
    private final UploadEvents events = mock(UploadEvents.class);
    private final ParseWorker worker = new ParseWorker(jobs, uploads, storage, extractor, events);
    private final ParseJob job =
            new ParseJob(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    3,
                    1,
                    UUID.randomUUID());

    @BeforeEach
    void setup() {
        when(uploads.find(job.tenantId(), job.documentId()))
                .thenReturn(
                        Optional.of(
                                new UploadRecord(
                                        job.documentId(),
                                        job.tenantId(),
                                        UUID.randomUUID(),
                                        "file.txt",
                                        DocumentFormat.TXT,
                                        "private",
                                        5,
                                        null,
                                        "a".repeat(64),
                                        UploadStatus.PROCESSING,
                                        3,
                                        Instant.now(),
                                        null,
                                        null,
                                        5L,
                                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                                        null)));
        when(storage.open(any()))
                .thenAnswer(
                        call -> new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiedSnapshotIsParsedBeforeAtomicSourceCommit() {
        var parsed =
                new ParsedDocument(
                        "apache-tika:3.3.3", List.of(new ParsedDocument.Section(null, "hello")));
        when(extractor.extract(any(), any())).thenReturn(parsed);
        when(jobs.complete(job, parsed)).thenReturn(true);
        worker.process(job);
        verify(jobs).complete(job, parsed);
        verify(events).record(UploadEvents.Outcome.PARSE_SUCCEEDED, job.documentId());
    }

    @Test
    void emptyExtractionFailsPermanentlyButChildUnavailableRetries() {
        when(extractor.extract(any(), any()))
                .thenThrow(
                        new TextExtractionFailure("EXTRACT_EMPTY"),
                        new TextExtractionFailure("PARSE_UNAVAILABLE"));
        worker.process(job);
        verify(jobs).failed(job, "EXTRACT_EMPTY", true);
        worker.process(job);
        verify(jobs).failed(job, "PARSE_UNAVAILABLE", false);
        verify(jobs, never()).complete(any(), any());
    }

    @Test
    void corruptedStorageIsRejectedBeforeParserAndStaleGenerationCannotStart() {
        when(storage.open(any()))
                .thenAnswer(
                        call -> new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)));
        worker.process(job);
        verify(jobs).failed(job, "CORRUPTED_FILE", true);
        verify(extractor, never()).extract(any(), any());
        when(uploads.find(job.tenantId(), job.documentId())).thenReturn(Optional.empty());
        worker.process(job);
        verify(jobs).failed(job, "PARSE_UNAVAILABLE", false);
    }
}
