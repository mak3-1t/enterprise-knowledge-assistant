package com.eka.ingestion.internal.parsing;

import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.scan.InspectionRejected;
import com.eka.ingestion.internal.scan.InspectionSnapshot;
import com.eka.ingestion.internal.service.UploadEvents;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eka.ingestion.parser.enabled", havingValue = "true")
public class ParseWorker {
    private final ParseJobRepository jobs;
    private final UploadRepository uploads;
    private final UploadStorage storage;
    private final TextExtractor extractor;
    private final UploadEvents events;

    public ParseWorker(
            ParseJobRepository jobs,
            UploadRepository uploads,
            UploadStorage storage,
            TextExtractor extractor,
            UploadEvents events) {
        this.jobs = jobs;
        this.uploads = uploads;
        this.storage = storage;
        this.extractor = extractor;
        this.events = events;
    }

    @Scheduled(
            fixedDelayString = "${eka.ingestion.parser.poll-delay-ms:500}",
            scheduler = "parseWorkerScheduler")
    public void tick() {
        jobs.claim().ifPresent(this::process);
    }

    void process(ParseJob job) {
        try {
            var row = uploads.find(job.tenantId(), job.documentId()).orElseThrow();
            if (row.status() != UploadStatus.PROCESSING || row.version() != job.generation()) {
                jobs.failed(job, "STALE_GENERATION", true);
                return;
            }
            try (var snapshot = InspectionSnapshot.create(storage, row)) {
                var parsed = extractor.extract(snapshot.file(), row.format());
                if (jobs.complete(job, parsed)) {
                    events.record(UploadEvents.Outcome.PARSE_SUCCEEDED, job.documentId());
                }
            }
        } catch (InspectionRejected rejected) {
            failure(job, rejected.getMessage(), true);
        } catch (TextExtractionFailure failed) {
            boolean permanent =
                    Set.of(
                                    "EXTRACT_EMPTY",
                                    "EXTRACT_LIMIT",
                                    "CORRUPTED_FILE",
                                    "TYPE_MISMATCH",
                                    "PASSWORD_PROTECTED",
                                    "MACRO_DETECTED",
                                    "ACTIVE_CONTENT_UNSUPPORTED",
                                    "FILE_TOO_LARGE")
                            .contains(failed.getMessage());
            failure(job, failed.getMessage(), permanent);
        } catch (RuntimeException unavailable) {
            failure(job, "PARSE_UNAVAILABLE", false);
        }
    }

    private void failure(ParseJob job, String code, boolean permanent) {
        if (jobs.failed(job, code, permanent)) {
            events.record(
                    permanent || job.attempts() >= 4
                            ? UploadEvents.Outcome.PARSE_FAILED
                            : UploadEvents.Outcome.PARSE_DEFERRED,
                    job.documentId());
        }
    }
}
