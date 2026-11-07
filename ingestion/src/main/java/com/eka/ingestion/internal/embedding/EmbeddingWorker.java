package com.eka.ingestion.internal.embedding;

import com.eka.ingestion.internal.chunking.DocumentChunkService;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class EmbeddingWorker {
    static final int MAX_CHUNKS = 4096;
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingWorker.class);
    private final EmbeddingJobRepository jobs;
    private final DocumentChunkService chunks;
    private final EmbeddingBatch embedder;
    private final int batchSize;

    EmbeddingWorker(
            EmbeddingJobRepository jobs,
            DocumentChunkService chunks,
            EmbeddingBatch embedder,
            EmbeddingSettings settings) {
        this.jobs = jobs;
        this.chunks = chunks;
        this.embedder = embedder;
        this.batchSize = settings.batchSize();
    }

    @Scheduled(fixedDelay = 1000, scheduler = "embeddingScheduler")
    public void poll() {
        try {
            jobs.claim().ifPresent(this::process);
        } catch (RuntimeException failure) {
            LOG.warn("Embedding poll unavailable");
        }
    }

    private void process(EmbeddingJob job) {
        try {
            var source = chunks.load(job.tenant(), job.document(), job.generation());
            if (source.isEmpty() || source.size() > MAX_CHUNKS) {
                jobs.failed(job, "EMBEDDING_CHUNK_LIMIT", true);
                return;
            }
            var result = new ArrayList<EmbeddingBatch.EmbeddedChunk>(source.size());
            for (int start = 0; start < source.size(); start += batchSize) {
                if (!jobs.renew(job)) {
                    return;
                }
                result.addAll(
                        embedder.embed(
                                source.subList(start, Math.min(start + batchSize, source.size()))));
            }
            if (jobs.complete(job, result)) {
                LOG.info("Embedding published for document {}", job.document());
            }
        } catch (EmbeddingFailure failure) {
            jobs.failed(job, failure.getMessage(), false);
        } catch (RuntimeException failure) {
            jobs.failed(job, "EMBEDDING_UNAVAILABLE", false);
        }
    }
}
