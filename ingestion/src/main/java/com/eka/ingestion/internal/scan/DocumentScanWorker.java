package com.eka.ingestion.internal.scan;

import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.service.UploadEvents;
import com.eka.ingestion.internal.storage.UploadStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eka.upload.scan.enabled", havingValue = "true")
public class DocumentScanWorker {
    private final ScanJobRepository jobs;
    private final UploadRepository uploads;
    private final UploadStorage storage;
    private final MalwareScanner malware;
    private final ContentInspector content;
    private final UploadEvents events;

    public DocumentScanWorker(
            ScanJobRepository jobs,
            UploadRepository uploads,
            UploadStorage storage,
            MalwareScanner malware,
            ContentInspector content,
            UploadEvents events) {
        this.jobs = jobs;
        this.uploads = uploads;
        this.storage = storage;
        this.malware = malware;
        this.content = content;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${eka.upload.scan.poll-delay-ms:2000}")
    public void tick() {
        jobs.dispatch();
        jobs.claim().ifPresent(this::process);
    }

    void process(ScanJob job) {
        try {
            var found = uploads.find(job.tenantId(), job.documentId());
            if (found.isEmpty()
                    || found.get().status() != UploadStatus.SCANNING
                    || job.scanVersion() == null
                    || found.get().version() != job.scanVersion()) {
                jobs.cancel(job);
                return;
            }
            var row = found.get();
            try (var snapshot = InspectionSnapshot.create(storage, row)) {
                malware.scan(snapshot.file());
                content.inspect(snapshot.file(), row.format());
            }
            if (jobs.clean(job)) {
                events.record(UploadEvents.Outcome.SCAN_PASSED, job.documentId());
            }
        } catch (InspectionRejected rejected) {
            if (jobs.rejected(job, rejected.getMessage())) {
                events.record(UploadEvents.Outcome.SCAN_REJECTED, job.documentId());
            }
        } catch (ScanUnavailable unavailable) {
            if (jobs.retry(job, unavailable.getMessage())) {
                events.record(UploadEvents.Outcome.SCAN_RETRY, job.documentId());
            }
        } catch (RuntimeException unavailable) {
            if (jobs.retry(job, "SCAN_UNAVAILABLE")) {
                events.record(UploadEvents.Outcome.SCAN_RETRY, job.documentId());
            }
        }
    }
}
