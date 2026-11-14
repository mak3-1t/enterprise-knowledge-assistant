package com.eka.ingestion.internal.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Records bounded, low-cardinality reranking outcomes without logging query or evidence text. */
public final class RerankingEvents {
    private static final Logger LOG = LoggerFactory.getLogger(RerankingEvents.class);
    private final ObjectProvider<MeterRegistry> registries;

    public RerankingEvents(ObjectProvider<MeterRegistry> registries) {
        this.registries = registries;
    }

    public void completed(Duration elapsed, int candidates, boolean fallback) {
        String outcome = fallback ? "fallback" : "success";
        if (fallback) {
            LOG.warn("retrieval_reranking_outcome={} candidate_count={}", outcome, candidates);
        } else {
            LOG.debug("retrieval_reranking_outcome={} candidate_count={}", outcome, candidates);
        }
        registries.ifAvailable(
                registry -> {
                    registry.counter("eka.retrieval.reranking.requests", "outcome", outcome)
                            .increment();
                    registry.timer("eka.retrieval.reranking.duration", "outcome", outcome)
                            .record(elapsed);
                    registry.summary("eka.retrieval.reranking.candidates", "outcome", outcome)
                            .record(candidates);
                });
    }
}
