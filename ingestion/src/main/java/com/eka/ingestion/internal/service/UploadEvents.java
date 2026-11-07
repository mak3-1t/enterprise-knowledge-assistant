package com.eka.ingestion.internal.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class UploadEvents {
    private static final Logger LOG = LoggerFactory.getLogger(UploadEvents.class);
    private final ObjectProvider<MeterRegistry> registries;

    public UploadEvents(ObjectProvider<MeterRegistry> registries) {
        this.registries = registries;
    }

    public void record(Outcome outcome, UUID document) {
        String label = outcome.name().toLowerCase(Locale.ROOT);
        LOG.info("upload_outcome={} document_id={}", label, document);
        registries.ifAvailable(
                registry -> registry.counter("eka.upload.events", "outcome", label).increment());
    }

    public enum Outcome {
        INTENT_RETURNED,
        COMPLETION_RETURNED,
        DELETED,
        RESTORED,
        PURGED,
        CLEANUP_DEFERRED,
        SCAN_PASSED,
        SCAN_REJECTED,
        SCAN_RETRY,
        OUTBOX_PUBLISHED,
        OUTBOX_STALE,
        OUTBOX_DEFERRED,
        PARSE_SUCCEEDED,
        PARSE_DEFERRED,
        PARSE_FAILED
    }
}
