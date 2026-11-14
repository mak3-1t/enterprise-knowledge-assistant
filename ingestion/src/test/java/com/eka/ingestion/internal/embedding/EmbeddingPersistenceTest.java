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
import com.eka.ingestion.internal.retrieval.LexicalSectionSearch;
import com.eka.ingestion.internal.retrieval.PgvectorDocumentRetrieval;
import com.eka.ingestion.internal.retrieval.RrfDocumentSearch;
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

    @Test
    void lexicalSearchFindsOnlyReadySectionsForTheAuthenticatedTenant() {
        UUID own = db.parsed(tenant, user, "Chính sách làm việc từ xa");
        var search =
                new LexicalSectionSearch(
                        () -> new AuthenticatedAccount(user, tenant, "VIEWER"), jdbc());
        assertThat(search.search("làm việc", 10)).isEmpty();
        var worker = worker(input -> List.of(vector()));
        worker.poll();
        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID foreign = db.parsed(otherTenant, otherUser, "Chính sách làm việc từ xa");
        worker.poll();
        assertThat(search.search("LÀM VIỆC", 10))
                .extracting(LexicalSectionSearch.Candidate::documentId)
                .containsExactly(own);
        assertThat(search.searchChunks("làm việc", "fixture", "v1", 10))
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(own);
        assertThat(search.searchChunks("làm việc", "fixture", "v1", 10).getFirst())
                .satisfies(
                        match -> {
                            assertThat(match.text()).contains("làm việc");
                            assertThat(match.lexicalMatch()).isTrue();
                        });
        var other =
                new LexicalSectionSearch(
                        () -> new AuthenticatedAccount(otherUser, otherTenant, "ADMIN"), jdbc());
        assertThat(other.search("làm việc", 1))
                .extracting(LexicalSectionSearch.Candidate::documentId)
                .containsExactly(foreign);
        assertThat(other.searchChunks("làm việc", "fixture", "v1", 10))
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(foreign);
        assertThat(search.search("trợ cấp", 10)).isEmpty();
        uploads().delete(tenant, user, false, own);
        assertThat(search.search("làm việc", 10)).isEmpty();
        assertThat(search.searchChunks("làm việc", "fixture", "v1", 10)).isEmpty();
    }

    @Test
    void roleAclFiltersVectorAndLexicalEvidenceBeforeHybridRanking() {
        UUID restricted = db.parsed(tenant, user, "Bảo mật dự án nội bộ chỉ dành cho quản lý.");
        worker(input -> List.of(vector())).poll();
        uploads().setReaderRoles(tenant, restricted, List.of("VIEWER"));

        var editor = new AuthenticatedAccount(user, tenant, "EDITOR");
        var viewer = new AuthenticatedAccount(user, tenant, "VIEWER");
        var admin = new AuthenticatedAccount(user, tenant, "ADMIN");
        var editorVector = new PgvectorDocumentRetrieval(() -> editor, jdbc());
        var editorLexical = new LexicalSectionSearch(() -> editor, jdbc());
        var viewerVector = new PgvectorDocumentRetrieval(() -> viewer, jdbc());
        var viewerLexical = new LexicalSectionSearch(() -> viewer, jdbc());
        var adminVector = new PgvectorDocumentRetrieval(() -> admin, jdbc());

        assertThat(editorVector.search(vector(), "fixture", "v1", 10)).isEmpty();
        assertThat(editorLexical.searchChunks("bảo mật", "fixture", "v1", 10)).isEmpty();
        assertThat(viewerVector.search(vector(), "fixture", "v1", 10))
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(restricted);
        assertThat(viewerLexical.searchChunks("bảo mật", "fixture", "v1", 10))
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(restricted);
        assertThat(adminVector.search(vector(), "fixture", "v1", 10))
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(restricted);
    }

    @Test
    void hybridSearchImprovesTheLabeledExactTermCaseOverVectorOnly() {
        UUID irrelevant = db.parsed(tenant, user, "The cafeteria subsidy is reviewed weekly.");
        UUID relevant =
                db.parsed(tenant, user, "Remote work is permitted up to two days each week.");
        var worker = worker(input -> List.of(vector()));
        worker.poll();
        worker.poll();
        storeVector(irrelevant, vector(1.0f, 0.0f));
        storeVector(relevant, vector(0.8f, 0.6f));

        var retrieval =
                new PgvectorDocumentRetrieval(
                        () -> new AuthenticatedAccount(user, tenant, "VIEWER"), jdbc());
        var vectorOnly = retrieval.search(vector(1.0f, 0.0f), "fixture", "v1", 10);
        var lexical =
                new LexicalSectionSearch(
                        () -> new AuthenticatedAccount(user, tenant, "VIEWER"), jdbc());
        var hybrid =
                new RrfDocumentSearch(
                        (question, limit) ->
                                retrieval.search(vector(1.0f, 0.0f), "fixture", "v1", limit),
                        lexical,
                        "fixture",
                        "v1",
                        true);
        var fused = hybrid.search("remote work days", 5);

        assertThat(vectorOnly)
                .extracting(DocumentRetrieval.Match::documentId)
                .containsExactly(irrelevant, relevant);
        assertThat(fused.getFirst().documentId()).isEqualTo(relevant);
        assertThat(reciprocalRank(vectorOnly, relevant)).isEqualTo(0.5);
        assertThat(reciprocalRank(fused, relevant)).isEqualTo(1.0);
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

    private void storeVector(UUID doc, float[] value) {
        jdbc().sql(
                        "UPDATE ingestion.document_chunk_embeddings SET embedding = CAST(? AS vector) "
                                + "WHERE tenant_id = ? AND document_id = ?")
                .param(vectorLiteral(value))
                .param(tenant)
                .param(doc)
                .update();
    }

    private static double reciprocalRank(List<DocumentRetrieval.Match> matches, UUID relevant) {
        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).documentId().equals(relevant)) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
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

    private static float[] vector(float first, float second) {
        var vector = new float[EmbeddingBatch.DIMENSIONS];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private static String vectorLiteral(float[] vector) {
        var literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }
}
