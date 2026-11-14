package com.eka.ingestion.internal.retrieval;

import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Finds lexical source-section candidates for the authenticated tenant. */
@Repository
public class LexicalSectionSearch {
    private final CurrentAccount current;
    private final JdbcClient jdbc;

    public LexicalSectionSearch(CurrentAccount current, JdbcClient jdbc) {
        this.current = current;
        this.jdbc = jdbc;
    }

    public List<Candidate> search(String terms, int limit) {
        var account = current.require();
        UUID tenant = account.tenantId();
        String role = account.role();
        if (terms == null || terms.isBlank() || terms.length() > 2000 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid lexical search options");
        }
        return jdbc.sql(
                        """
                SELECT s.document_id, s.generation, s.ordinal, s.page,
                    ts_rank(s.lexical_terms, q.terms) AS rank
                FROM ingestion.document_source_sections s
                JOIN ingestion.documents d ON d.tenant_id = s.tenant_id AND d.id = s.document_id
                    AND d.source_generation = s.generation
                CROSS JOIN (SELECT plainto_tsquery('pg_catalog.simple', ?) AS terms) q
                WHERE s.tenant_id = ? AND d.upload_status = 'READY' AND d.purged_at IS NULL
                    AND (? = 'ADMIN' OR d.reader_roles @> ARRAY[?]::TEXT[])
                    AND s.lexical_terms @@ q.terms
                ORDER BY rank DESC, s.document_id, s.ordinal
                LIMIT ?
                """)
                .param(terms)
                .param(tenant)
                .param(role)
                .param(role)
                .param(limit)
                .query(
                        (rs, ignored) ->
                                new Candidate(
                                        rs.getObject("document_id", UUID.class),
                                        rs.getLong("generation"),
                                        rs.getInt("ordinal"),
                                        rs.getObject("page", Integer.class),
                                        rs.getDouble("rank")))
                .list();
    }

    public List<Match> searchChunks(String terms, String model, String modelVersion, int limit) {
        var account = current.require();
        UUID tenant = account.tenantId();
        String role = account.role();
        if (terms == null
                || terms.isBlank()
                || terms.length() > 2000
                || !validIdentity(model)
                || !validIdentity(modelVersion)
                || limit < 1
                || limit > 50) {
            throw new IllegalArgumentException("Invalid lexical search options");
        }
        return jdbc.sql(
                        """
                WITH input_query AS (
                    SELECT plainto_tsquery('pg_catalog.simple', ?) AS terms
                ), matched_sections AS (
                    SELECT s.tenant_id, s.document_id, s.generation, s.ordinal, s.page,
                        s.source_text, q.terms,
                        ts_rank(s.lexical_terms, q.terms) AS section_rank
                    FROM ingestion.document_source_sections s
                    JOIN ingestion.documents d ON d.tenant_id = s.tenant_id
                        AND d.id = s.document_id AND d.source_generation = s.generation
                    CROSS JOIN input_query q
                    WHERE s.tenant_id = ? AND d.upload_status = 'READY'
                        AND (? = 'ADMIN' OR d.reader_roles @> ARRAY[?]::TEXT[])
                        AND d.purged_at IS NULL AND s.lexical_terms @@ q.terms
                ), scored_chunks AS (
                    SELECT d.id AS document_id, d.filename, e.generation, e.ordinal AS chunk_ordinal,
                        s.ordinal AS section_ordinal, s.page, e.start_offset, e.end_offset,
                        s.section_rank,
                        ts_rank(
                            to_tsvector(
                                'pg_catalog.simple',
                                substring(s.source_text FROM e.start_offset + 1
                                    FOR e.end_offset - e.start_offset)),
                            s.terms) AS chunk_rank,
                        substring(s.source_text FROM e.start_offset + 1
                            FOR e.end_offset - e.start_offset) AS chunk_text
                    FROM matched_sections s
                    JOIN ingestion.documents d ON d.tenant_id = s.tenant_id
                        AND d.id = s.document_id AND d.source_generation = s.generation
                    JOIN ingestion.document_chunk_embeddings e ON e.tenant_id = s.tenant_id
                        AND e.document_id = s.document_id AND e.generation = s.generation
                        AND e.section_ordinal = s.ordinal
                    WHERE e.model = ? AND e.model_version = ?
                ), ranked_chunks AS (
                    SELECT *, row_number() OVER (
                        PARTITION BY document_id, generation, section_ordinal
                        ORDER BY chunk_rank DESC, chunk_ordinal) AS section_chunk_rank
                    FROM scored_chunks
                    WHERE chunk_rank > 0
                )
                SELECT document_id, filename, generation, chunk_ordinal, section_ordinal, page,
                    start_offset, end_offset, chunk_text
                FROM ranked_chunks
                WHERE section_chunk_rank <= 3
                ORDER BY section_rank DESC, chunk_rank DESC, document_id, chunk_ordinal
                LIMIT ?
                """)
                .param(terms)
                .param(tenant)
                .param(role)
                .param(role)
                .param(model)
                .param(modelVersion)
                .param(limit)
                .query(
                        (rs, ignored) ->
                                new Match(
                                        rs.getObject("document_id", UUID.class),
                                        rs.getString("filename"),
                                        rs.getLong("generation"),
                                        rs.getInt("chunk_ordinal"),
                                        rs.getInt("section_ordinal"),
                                        rs.getObject("page", Integer.class),
                                        rs.getInt("start_offset"),
                                        rs.getInt("end_offset"),
                                        rs.getString("chunk_text"),
                                        1.0,
                                        true))
                .list();
    }

    private static boolean validIdentity(String value) {
        return value != null && value.matches("[A-Za-z0-9_./:-]{1,128}");
    }

    public record Candidate(
            UUID documentId, long generation, int sectionOrdinal, Integer page, double rank) {}
}
