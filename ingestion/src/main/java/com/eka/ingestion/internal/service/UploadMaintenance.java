package com.eka.ingestion.internal.service;

import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.storage.UploadStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "eka.upload.enabled", havingValue = "true")
public class UploadMaintenance {
    private final UploadRepository uploads;
    private final UploadStorage storage;
    private final UploadEvents events;

    public UploadMaintenance(UploadRepository uploads, UploadStorage storage, UploadEvents events) {
        this.uploads = uploads;
        this.storage = storage;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${eka.upload.cleanup-delay-ms:3600000}")
    public void clean() {
        uploads.expirePending();
        for (var row : uploads.cleanupCandidates(100)) {
            try {
                if (uploads.purge(row.tenantId(), row.id(), () -> storage.remove(row))) {
                    events.record(UploadEvents.Outcome.PURGED, row.id());
                }
            } catch (RuntimeException failure) {
                events.record(UploadEvents.Outcome.CLEANUP_DEFERRED, row.id());
            }
        }
    }
}
