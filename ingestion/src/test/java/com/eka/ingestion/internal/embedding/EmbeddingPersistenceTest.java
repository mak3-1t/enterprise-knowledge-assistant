package com.eka.ingestion.internal.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.ingestion.api.DocumentRetrieval;
import com.eka.ingestion.internal.chunking.ChunkSettings;
import com.eka.ingestion.internal.chunking.DocumentChunkService;
import com.eka.ingestion.internal.parsing.ParseDatabase;
import com.eka.ingestion.internal.parsing.ParseJobRepository;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.retrieval.PgvectorDocumentRetrieval;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class EmbeddingPersistenceTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static ParseDatabase db;
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final EmbeddingSettings settings =
            new EmbeddingSettings(URI.create("http://localhost:11434"), "fixture", "v1", 1);

    @BeforeAll
    static void database() {
        db = new ParseDatabase(POSTGRES, EmbeddingJobRepository.class);
    }

    @AfterAll
    static void close() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() {
        db.reset();
    }

    @Test
    void partialProviderFailureRetriesWithoutPublishingAndSuccessfulRetryPublishesOnce() {
        UUID doc = db.parsed(tenant, user, "a".repeat(120));
        var calls = new AtomicInteger();
        var worker =
                worker(
                        text -> {
                            if (calls.incrementAndGet() == 2) {
                                throw new EmbeddingFailure("EMBEDDING_PROVIDER_UNAVAILABLE");
                            }
                            return List.of(vector());
                        });
        worker.poll();
        assertThat(status(doc)).isEqualTo(UploadStatus.PROCESSING);
        assertThat(vectorCount(doc)).isZero();
        assertThat(jobState(doc)).isEqualTo("RETRY");
        jdbc().sql("UPDATE ingestion.document_embedding_jobs SET available_at = clock_timestamp()")
                .update();
        worker.poll();
        worker.poll();
        assertThat(status(doc)).isEqualTo(UploadStatus.READY);
        assertThat(jobState(doc)).isEqualTo("SUCCEEDED");
        assertThat(vectorCount(doc)).isEqualTo(2);
        assertThat(
                        jdbc().sql(
                                        """
                SELECT vector_dims(embedding) FROM ingestion.document_chunk_embeddings
                WHERE tenant_id = ? AND document_id = ? ORDER BY ordinal
                """)
                                .param(tenant)
                                .param(doc)
                                .query(Integer.class)
                                .list())
                .containsExactly(1024, 1024);
        assertThat(
                        jdbc().sql(
                                        "SELECT count(*) FROM ingestion.document_chunk_embeddings WHERE tenant_id = ?")
                                .param(UUID.randomUUID())
                                .query(Long.class)
                                .single())
                .isZero();
        uploads().delete(tenant, user, false, doc);
        assertThat(vectorCount(doc)).isZero();
    }

    @Test
    void databaseFailureRollsBackAndExpiredOrDeletedLeaseCannotPublish() {
        UUID doc = db.parsed(tenant, user, "a".repeat(120));
        var jobs = db.bean(EmbeddingJobRepository.class);
        var first = jobs.claim().orElseThrow();
        assertThat(jobs.claim()).isEmpty();
        var valid =
                new EmbeddingBatch(
                                text -> List.of(vector(), vector()),
                                new EmbeddingSettings(
                                        settings.baseUrl(),
                                        settings.model(),
                                        settings.modelVersion(),
                                        2))
                        .embed(chunks().load(tenant, doc, first.generation()));
        var malformed =
                new EmbeddingBatch.EmbeddedChunk(
                        valid.get(1).chunk(), "fixture", "v1", new float[1]);
        assertThatThrownBy(() -> jobs.complete(first, List.of(valid.getFirst(), malformed)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(status(doc)).isEqualTo(UploadStatus.PROCESSING);
        assertThat(vectorCount(doc)).isZero();
        jdbc().sql("UPDATE ingestion.document_embedding_jobs SET lease_until = clock_timestamp()")
                .update();
        var replacement = jobs.claim().orElseThrow();
        assertThat(replacement.attempts()).isEqualTo(2);
        assertThat(jobs.complete(first, valid)).isFalse();
        uploads().delete(tenant, user, false, doc);
        assertThat(jobs.complete(replacement, valid)).isFalse();
        assertThat(vectorCount(doc)).isZero();
    }

    @Test
    void exhaustedEmbeddingCanBeRequeuedOnceAndClearsOldSource() throws Exception {
        UUID doc = db.parsed(tenant, user, "hello");
        var jobs = db.bean(EmbeddingJobRepository.class);
        for (int attempt = 1; attempt <= 4; attempt++) {
            var job = jobs.claim().orElseThrow();
            assertThat(job.attempts()).isEqualTo(attempt);
            jobs.failed(job, "EMBEDDING_PROVIDER_UNAVAILABLE", false);
            jdbc().sql(
                            "UPDATE ingestion.document_embedding_jobs SET available_at = clock_timestamp()")
                    .update();
        }
        var failed = uploads().find(tenant, doc).orElseThrow();
        assertThat(failed.status()).isEqualTo(UploadStatus.FAILED);
        assertThat(db.recover(tenant, doc, failed.version())).isTrue();
        assertThat(db.recover(tenant, doc, failed.version())).isFalse();
        assertThat(status(doc)).isEqualTo(UploadStatus.UPLOADED);
        assertThat(jobState(doc)).isEqualTo("DEAD");
        assertThat(db.bean(ParseJobRepository.class).loadCurrent(tenant, doc)).isEmpty();
        assertThat(
                        jdbc().sql(
                                        "SELECT count(*) FROM ingestion.document_source_sections WHERE tenant_id = ? AND document_id = ?")
                                .param(tenant)
                                .param(doc)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(vectorCount(doc)).isZero();
    }

    @Test
    void retrievalFiltersTenantModelAndLifecycleBeforeRankingAndPreservesUnicodeOffsets()
            throws Exception {
        String text = "🚀".repeat(64) + "Tiếng Việt";
        UUID own = db.parsed(tenant, user, text);
        var retrieval =
                new PgvectorDocumentRetrieval(
                        () -> new AuthenticatedAccount(user, tenant, "VIEWER"), jdbc());
        assertThat(retrieval.search(vector(), "fixture", "v1", 10)).isEmpty();
        var worker = worker(input -> List.of(vector()));
        worker.poll();
        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID foreign = db.parsed(otherTenant, otherUser, "foreign secret");
        worker.poll();

        var matches = retrieval.search(vector(), "fixture", "v1", 10);
        assertThat(matches)
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(own, own);
        assertThat(matches)
                .extracting(DocumentRetrieval.Match::text)
                .containsExactly("🚀".repeat(64), "Tiếng Việt");
        assertThat(matches.get(1).startOffset()).isEqualTo(128);
        assertThat(matches.getFirst().distance()).isZero();
        assertThat(retrieval.search(vector(), "fixture", "v1", 1)).hasSize(1);
        assertThat(retrieval.search(vector(), "fixture", "v2", 10)).isEmpty();
        var foreignRetrieval =
                new PgvectorDocumentRetrieval(
                        () -> new AuthenticatedAccount(otherUser, otherTenant, "ADMIN"), jdbc());
        assertThat(foreignRetrieval.search(vector(), "fixture", "v1", 10))
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(foreign);
        uploads().delete(tenant, user, false, own);
        assertThat(retrieval.search(vector(), "fixture", "v1", 10)).isEmpty();
    }

    private EmbeddingWorker worker(EmbeddingProvider provider) {
        return new EmbeddingWorker(
                db.bean(EmbeddingJobRepository.class),
                chunks(),
                new EmbeddingBatch(provider, settings),
                settings);
    }

    private DocumentChunkService chunks() {
        return new DocumentChunkService(
                db.bean(ParseJobRepository.class),
                new ChunkSettings(ChunkSettings.Strategy.FIXED, 64, 0));
    }

    private JdbcClient jdbc() {
        return db.bean(JdbcClient.class);
    }

    private UploadRepository uploads() {
        return db.bean(UploadRepository.class);
    }

    private UploadStatus status(UUID doc) {
        return uploads().find(tenant, doc).orElseThrow().status();
    }

    private long vectorCount(UUID doc) {
        return jdbc().sql(
                        "SELECT count(*) FROM ingestion.document_chunk_embeddings WHERE tenant_id = ? AND document_id = ?")
                .param(tenant)
                .param(doc)
                .query(Long.class)
                .single();
    }

    private String jobState(UUID doc) {
        return jdbc().sql(
                        "SELECT state FROM ingestion.document_embedding_jobs WHERE tenant_id = ? AND document_id = ?")
                .param(tenant)
                .param(doc)
                .query(String.class)
                .single();
    }

    private static float[] vector() {
        var vector = new float[EmbeddingBatch.DIMENSIONS];
        vector[0] = 1;
        return vector;
    }
}
