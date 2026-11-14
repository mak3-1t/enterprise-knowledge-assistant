package com.eka.ingestion.internal.retrieval;

import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.DocumentRetrieval;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PgvectorDocumentRetrieval implements DocumentRetrieval {
    private final CurrentAccount current;
    private final JdbcClient jdbc;

    public PgvectorDocumentRetrieval(CurrentAccount current, JdbcClient jdbc) {
        this.current = current;
        this.jdbc = jdbc;
    }

    @Override
    public List<Match> search(float[] query, String model, String modelVersion, int limit) {
        UUID tenant = current.require().tenantId();
        String role = current.require().role();
        if (limit < 1 || limit > 10 || !validIdentity(model) || !validIdentity(modelVersion)) {
            throw new IllegalArgumentException("Invalid retrieval options");
        }
        return jdbc.sql(
                        """
                SELECT d.id, d.filename, e.generation, e.ordinal, e.section_ordinal, s.page,
                    e.start_offset, e.end_offset, s.source_text,
                    e.embedding <=> CAST(? AS vector) AS distance
                FROM ingestion.document_chunk_embeddings e
                JOIN ingestion.documents d ON d.tenant_id = e.tenant_id AND d.id = e.document_id
                    AND d.source_generation = e.generation
                JOIN ingestion.document_source_sections s ON s.tenant_id = e.tenant_id
                    AND s.document_id = e.document_id AND s.generation = e.generation
                    AND s.ordinal = e.section_ordinal
                WHERE e.tenant_id = ? AND d.upload_status = 'READY' AND d.purged_at IS NULL
                    AND (? = 'ADMIN' OR d.reader_roles @> ARRAY[?]::TEXT[])
                    AND e.model = ? AND e.model_version = ?
                ORDER BY distance, d.id, e.ordinal
                LIMIT ?
                """)
                .param(vector(query))
                .param(tenant)
                .param(role)
                .param(role)
                .param(model)
                .param(modelVersion)
                .param(limit)
                .query(
                        (rs, ignored) -> {
                            int start = rs.getInt("start_offset");
                            int end = rs.getInt("end_offset");
                            String text = rs.getString("source_text");
                            return new Match(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("filename"),
                                    rs.getLong("generation"),
                                    rs.getInt("ordinal"),
                                    rs.getInt("section_ordinal"),
                                    rs.getObject("page", Integer.class),
                                    start,
                                    end,
                                    text.substring(start, end),
                                    rs.getDouble("distance"));
                        })
                .list();
    }

    private static boolean validIdentity(String value) {
        return value != null && value.matches("[A-Za-z0-9_./:-]{1,128}");
    }

    private static String vector(float[] query) {
        if (query == null || query.length != 1024) {
            throw new IllegalArgumentException("Invalid query vector dimensions");
        }
        boolean nonzero = false;
        var literal = new StringJoiner(",", "[", "]");
        for (float coordinate : query) {
            if (!Float.isFinite(coordinate)) {
                throw new IllegalArgumentException("Nonfinite query vector");
            }
            nonzero |= coordinate != 0;
            literal.add(Float.toString(coordinate));
        }
        if (!nonzero) {
            throw new IllegalArgumentException("Zero query vector");
        }
        return literal.toString();
    }
}
