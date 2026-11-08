package com.eka.chat.internal;

import com.eka.auth.api.AuthenticatedAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ConversationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ConversationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Conversation create(AuthenticatedAccount account) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO chat.conversations (id, tenant_id, owner_id) VALUES (?, ?, ?)")
                .param(id)
                .param(account.tenantId())
                .param(account.userId())
                .update();
        return new Conversation(id, 0);
    }

    Conversation require(AuthenticatedAccount account, UUID id) {
        return jdbc.sql(
                        "SELECT revision FROM chat.conversations WHERE id = ? AND tenant_id = ? AND owner_id = ?")
                .param(id)
                .param(account.tenantId())
                .param(account.userId())
                .query(Long.class)
                .optional()
                .map(revision -> new Conversation(id, revision))
                .orElseThrow(() -> new ConversationConflict("CONVERSATION_NOT_FOUND"));
    }

    List<String> recentQuestions(AuthenticatedAccount account, UUID id, long revision) {
        return jdbc.sql(
                        """
                SELECT t.question FROM chat.turns t JOIN chat.conversations c ON c.id = t.conversation_id
                WHERE c.id = ? AND c.tenant_id = ? AND c.owner_id = ? AND t.revision <= ?
                ORDER BY t.revision DESC LIMIT 6
                """)
                .param(id)
                .param(account.tenantId())
                .param(account.userId())
                .param(revision)
                .query(String.class)
                .list();
    }

    List<Turn> history(AuthenticatedAccount account, UUID id, long before) {
        return jdbc.sql(
                        """
                SELECT t.revision, t.question, CASE WHEN EXISTS (
                    SELECT 1 FROM jsonb_array_elements(t.response -> 'citations') AS source(citation)
                    WHERE NOT EXISTS (
                        SELECT 1 FROM ingestion.documents d WHERE d.tenant_id = c.tenant_id
                            AND d.id = CAST(source.citation ->> 'documentId' AS uuid)
                            AND d.source_generation = CAST(source.citation ->> 'sourceGeneration' AS bigint)
                            AND d.upload_status = 'READY' AND d.purged_at IS NULL
                    )
                ) THEN NULL ELSE CAST(t.response AS text) END AS response
                FROM chat.turns t JOIN chat.conversations c ON c.id = t.conversation_id
                WHERE c.id = ? AND c.tenant_id = ? AND c.owner_id = ? AND t.revision < ?
                ORDER BY t.revision DESC LIMIT 20
                """)
                .param(id)
                .param(account.tenantId())
                .param(account.userId())
                .param(before)
                .query(
                        (rs, ignored) -> {
                            String response = rs.getString("response");
                            return new Turn(
                                    rs.getLong("revision"),
                                    rs.getString("question"),
                                    response == null ? null : json.readTree(response));
                        })
                .list();
    }

    @Transactional
    public void append(
            AuthenticatedAccount account,
            UUID id,
            long expected,
            String question,
            RagService.Answer answer) {
        int changed =
                jdbc.sql(
                                """
                UPDATE chat.conversations SET revision = revision + 1
                WHERE id = ? AND tenant_id = ? AND owner_id = ? AND revision = ?
                """)
                        .param(id)
                        .param(account.tenantId())
                        .param(account.userId())
                        .param(expected)
                        .update();
        if (changed != 1) {
            throw new ConversationConflict("CONVERSATION_CHANGED");
        }
        jdbc.sql(
                        """
                INSERT INTO chat.turns (conversation_id, revision, question, response)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """)
                .param(id)
                .param(expected + 1)
                .param(question)
                .param(json.writeValueAsString(answer))
                .update();
    }

    record Conversation(UUID id, long revision) {}

    record Turn(long revision, String question, JsonNode response) {}
}
