package com.eka.auth.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SessionRepository {
    private final JdbcClient jdbc;
    private final AccountRepository accounts;

    public SessionRepository(JdbcClient jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Transactional
    public <T> Optional<T> create(
            SessionRecord session, String hash, Instant now, Function<SessionRecord, T> issue) {
        if (accounts.findActiveIdentity(session.tenantId(), session.userId()).isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql(
                        """
                INSERT INTO auth.sessions (id, tenant_id, account_id, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(session.id())
                .param(session.tenantId())
                .param(session.userId())
                .param(time(now))
                .param(time(session.expiresAt()))
                .update();
        insertRefresh(hash, session.id(), now);
        // Signing is local; any signer/insert/commit failure rolls back the entire issuance.
        return Optional.of(issue.apply(session));
    }

    @Transactional
    public <T> Optional<T> rotate(
            String oldHash, String nextHash, Instant now, Function<SessionRecord, T> issue) {
        var owner =
                jdbc.sql("SELECT session_id FROM auth.refresh_tokens WHERE token_hash = ?")
                        .param(oldHash)
                        .query(UUID.class)
                        .optional();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        // Every refresh in one family locks this same row first, including replay requests.
        var locked =
                jdbc.sql("SELECT * FROM auth.sessions WHERE id = ? FOR UPDATE")
                        .param(owner.get())
                        .query(
                                (rs, row) ->
                                        new LockedSession(
                                                map(rs), rs.getTimestamp("revoked_at") != null))
                        .optional();
        if (locked.isEmpty()
                || locked.get().revoked()
                || !now.isBefore(locked.get().session().expiresAt())) {
            return Optional.empty();
        }
        var session = locked.get().session();
        boolean consumed =
                jdbc.sql(
                                "SELECT consumed_at IS NOT NULL FROM auth.refresh_tokens WHERE token_hash = ?")
                        .param(oldHash)
                        .query(Boolean.class)
                        .single();
        if (consumed
                || accounts.findActiveIdentity(session.tenantId(), session.userId()).isEmpty()) {
            revoke(session.id(), session.tenantId(), session.userId(), now);
            // Return normally so replay revocation COMMITS. The service raises 401 afterwards.
            return Optional.empty();
        }
        T result = issue.apply(session);
        jdbc.sql("UPDATE auth.refresh_tokens SET consumed_at = ? WHERE token_hash = ?")
                .param(time(now))
                .param(oldHash)
                .update();
        insertRefresh(nextHash, session.id(), now);
        return Optional.of(result);
    }

    public boolean isActive(UUID session, UUID tenant, UUID user, Instant now) {
        return jdbc.sql(
                                """
                SELECT count(*) FROM auth.sessions
                WHERE id = ? AND tenant_id = ? AND account_id = ?
                  AND revoked_at IS NULL AND expires_at > ?
                """)
                        .param(session)
                        .param(tenant)
                        .param(user)
                        .param(time(now))
                        .query(Long.class)
                        .single()
                == 1;
    }

    public boolean revoke(UUID session, UUID tenant, UUID user, Instant now) {
        return jdbc.sql(
                                """
                UPDATE auth.sessions SET revoked_at = ?
                WHERE id = ? AND tenant_id = ? AND account_id = ? AND revoked_at IS NULL
                """)
                        .param(time(now))
                        .param(session)
                        .param(tenant)
                        .param(user)
                        .update()
                == 1;
    }

    private void insertRefresh(String hash, UUID session, Instant now) {
        jdbc.sql(
                        "INSERT INTO auth.refresh_tokens (token_hash, session_id, created_at) VALUES (?, ?, ?)")
                .param(hash)
                .param(session)
                .param(time(now))
                .update();
    }

    private static SessionRecord map(ResultSet rs) throws SQLException {
        return new SessionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant());
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record LockedSession(SessionRecord session, boolean revoked) {}
}
