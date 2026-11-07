package com.eka.ingestion.internal.embedding;

import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class EmbeddingJobRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final UploadRepository uploads;

    EmbeddingJobRepository(JdbcClient jdbc, JdbcTemplate batches, UploadRepository uploads) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.uploads = uploads;
    }

    @Transactional
    public Optional<EmbeddingJob> claim() {
        UUID token = UUID.randomUUID();
        var candidate =
                jdbc.sql(
                                """
                SELECT d.tenant_id, d.id, d.source_generation, d.version, COALESCE(j.attempts, 0) AS attempts
                FROM ingestion.documents d
                LEFT JOIN ingestion.document_embedding_jobs j ON j.tenant_id = d.tenant_id
                    AND j.document_id = d.id AND j.generation = d.source_generation
                WHERE d.upload_status = 'PROCESSING' AND d.source_generation IS NOT NULL
                    AND (j.state IS NULL OR (j.state = 'RETRY' AND j.available_at <= clock_timestamp())
                        OR (j.state = 'RUNNING' AND j.lease_until <= clock_timestamp()))
                ORDER BY d.updated_at, d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """)
                        .query(
                                (rs, ignored) ->
                                        new EmbeddingJob(
                                                rs.getObject("tenant_id", UUID.class),
                                                rs.getObject("id", UUID.class),
                                                rs.getLong("source_generation"),
                                                rs.getLong("version"),
                                                rs.getInt("attempts") + 1,
                                                token))
                        .optional();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        var selected = candidate.orElseThrow();
        // The candidate join may see a snapshot from before another claimant released the document
        // lock.
        int attempts =
                jdbc.sql(
                                """
                SELECT CASE WHEN (state = 'RETRY' AND available_at <= clock_timestamp())
                    OR (state = 'RUNNING' AND lease_until <= clock_timestamp())
                    THEN attempts ELSE -1 END
                FROM ingestion.document_embedding_jobs
                WHERE tenant_id = ? AND document_id = ? AND generation = ?
                """)
                        .param(selected.tenant())
                        .param(selected.document())
                        .param(selected.generation())
                        .query(Integer.class)
                        .optional()
                        .orElse(0);
        if (attempts < 0) {
            return Optional.empty();
        }
        var job =
                new EmbeddingJob(
                        selected.tenant(),
                        selected.document(),
                        selected.generation(),
                        selected.version(),
                        attempts + 1,
                        token);
        if (job.attempts() > 4) {
            uploads.fail(
                    job.tenant(),
                    job.document(),
                    job.version(),
                    false,
                    "EMBEDDING_RETRY_EXHAUSTED");
            state(job, "DEAD", "EMBEDDING_RETRY_EXHAUSTED", 0);
            return Optional.empty();
        }
        jdbc.sql(
                        """
                INSERT INTO ingestion.document_embedding_jobs AS j
                    (tenant_id, document_id, generation, state, attempts, lease_token, lease_until)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, clock_timestamp() + INTERVAL '5 minutes')
                ON CONFLICT (tenant_id, document_id, generation) DO UPDATE SET state = 'RUNNING',
                    attempts = EXCLUDED.attempts, lease_token = EXCLUDED.lease_token,
                    lease_until = EXCLUDED.lease_until, error_code = NULL
                """)
                .param(job.tenant())
                .param(job.document())
                .param(job.generation())
                .param(job.attempts())
                .param(token)
                .update();
        return Optional.of(job);
    }

    @Transactional
    public boolean renew(EmbeddingJob job) {
        if (!lockCurrent(job)) {
            return false;
        }
        return jdbc.sql(
                                """
                UPDATE ingestion.document_embedding_jobs SET lease_until = clock_timestamp() + INTERVAL '5 minutes'
                WHERE tenant_id = ? AND document_id = ? AND generation = ?
                    AND state = 'RUNNING' AND lease_token = ? AND lease_until > clock_timestamp()
                """)
                        .param(job.tenant())
                        .param(job.document())
                        .param(job.generation())
                        .param(job.token())
                        .update()
                == 1;
    }

    @Transactional
    public boolean complete(EmbeddingJob job, List<EmbeddingBatch.EmbeddedChunk> chunks) {
        if (!renew(job)) {
            return false;
        }
        if (chunks.isEmpty() || chunks.size() > EmbeddingWorker.MAX_CHUNKS) {
            throw new EmbeddingFailure("EMBEDDING_CHUNK_LIMIT");
        }
        for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
            var chunk = chunks.get(ordinal).chunk();
            if (!job.tenant().equals(chunk.tenantId())
                    || !job.document().equals(chunk.documentId())
                    || job.generation() != chunk.sourceGeneration()
                    || chunk.ordinal() != ordinal) {
                throw new EmbeddingFailure("EMBEDDING_BATCH_SCOPE");
            }
        }
        batches.batchUpdate(
                """
                INSERT INTO ingestion.document_chunk_embeddings (tenant_id, document_id, generation,
                    ordinal, section_ordinal, start_offset, end_offset, model, model_version, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                """,
                chunks,
                64,
                (statement, embedded) -> {
                    var chunk = embedded.chunk();
                    statement.setObject(1, job.tenant());
                    statement.setObject(2, job.document());
                    statement.setLong(3, job.generation());
                    statement.setInt(4, chunk.ordinal());
                    statement.setInt(5, chunk.sectionOrdinal());
                    statement.setInt(6, chunk.startOffset());
                    statement.setInt(7, chunk.endOffset());
                    statement.setString(8, embedded.model());
                    statement.setString(9, embedded.modelVersion());
                    statement.setString(10, vectorLiteral(embedded.vector()));
                });
        if (!renew(job)
                || !uploads.advance(
                        job.tenant(),
                        job.document(),
                        job.version(),
                        UploadStatus.PROCESSING,
                        UploadStatus.READY)) {
            throw new EmbeddingFailure("EMBEDDING_LEASE_LOST");
        }
        state(job, "SUCCEEDED", null, 0);
        return true;
    }

    @Transactional
    public void failed(EmbeddingJob job, String code, boolean permanent) {
        if (!renew(job)) {
            return;
        }
        if (permanent || job.attempts() == 4) {
            uploads.fail(job.tenant(), job.document(), job.version(), false, code);
            state(job, "DEAD", code, 0);
            return;
        }
        state(job, "RETRY", code, 5 * (1 << (job.attempts() - 1)));
    }

    private boolean lockCurrent(EmbeddingJob job) {
        return jdbc.sql(
                        """
                SELECT id FROM ingestion.documents WHERE tenant_id = ? AND id = ?
                    AND source_generation = ? AND version = ? AND upload_status = 'PROCESSING'
                FOR UPDATE
                """)
                .param(job.tenant())
                .param(job.document())
                .param(job.generation())
                .param(job.version())
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private void state(EmbeddingJob job, String state, String code, int delay) {
        jdbc.sql(
                        """
                UPDATE ingestion.document_embedding_jobs SET state = ?, error_code = ?,
                    available_at = clock_timestamp() + (? * INTERVAL '1 second'),
                    lease_token = NULL, lease_until = NULL
                WHERE tenant_id = ? AND document_id = ? AND generation = ?
                """)
                .param(state)
                .param(code)
                .param(delay)
                .param(job.tenant())
                .param(job.document())
                .param(job.generation())
                .update();
    }

    private static String vectorLiteral(float[] vector) {
        var literal = new StringJoiner(",", "[", "]");
        for (float coordinate : vector) {
            literal.add(Float.toString(coordinate));
        }
        return literal.toString();
    }
}
