package com.eka.auth.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Auth-owned read access. No unscoped account lookup/list operation is exposed. */
@Repository
public class AccountRepository {
    private static final String SUMMARY_COLUMNS =
            "a.id, a.tenant_id, a.email, a.role, a.active, a.created_at";
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountSummary> findById(UUID tenantId, UUID accountId) {
        return jdbc.sql(
                        "SELECT "
                                + SUMMARY_COLUMNS
                                + " FROM auth.accounts a WHERE a.tenant_id = :tenant AND a.id = :id")
                .param("tenant", Objects.requireNonNull(tenantId))
                .param("id", Objects.requireNonNull(accountId))
                .query(AccountRepository::summary)
                .optional();
    }

    public List<AccountSummary> findMembers(UUID tenantId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("Limit must be 1..100 and offset non-negative");
        }
        return jdbc.sql(
                        "SELECT "
                                + SUMMARY_COLUMNS
                                + " FROM auth.accounts a WHERE a.tenant_id = :tenant"
                                + " ORDER BY a.id LIMIT :limit OFFSET :offset")
                .param("tenant", Objects.requireNonNull(tenantId))
                .param("limit", limit)
                .param("offset", offset)
                .query(AccountRepository::summary)
                .list();
    }

    /** Narrow credential lookup, always constrained by tenant slug and email. */
    public Optional<CredentialRecord> findCredentials(String tenantSlug, String email) {
        return jdbc.sql(
                        "SELECT "
                                + SUMMARY_COLUMNS
                                + ", a.password_hash, t.active AS tenant_active FROM auth.accounts a"
                                + " JOIN auth.tenants t ON t.id = a.tenant_id"
                                + " WHERE t.slug = :slug AND a.email = :email")
                .param("slug", Objects.requireNonNull(tenantSlug))
                .param("email", Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT))
                .query(
                        (rs, row) ->
                                new CredentialRecord(
                                        summary(rs, row),
                                        rs.getString("password_hash"),
                                        rs.getBoolean("tenant_active")))
                .optional();
    }

    /** Only active account/tenant pairs may become authenticated principals. */
    public Optional<AccountSummary> findActiveIdentity(UUID tenantId, UUID accountId) {
        return jdbc.sql(
                        "SELECT "
                                + SUMMARY_COLUMNS
                                + " FROM auth.accounts a JOIN auth.tenants t ON t.id = a.tenant_id"
                                + " WHERE a.tenant_id = :tenant AND a.id = :id AND a.active AND t.active")
                .param("tenant", Objects.requireNonNull(tenantId))
                .param("id", Objects.requireNonNull(accountId))
                .query(AccountRepository::summary)
                .optional();
    }

    private static AccountSummary summary(ResultSet rs, int row) throws SQLException {
        return new AccountSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("email"),
                AccountRole.valueOf(rs.getString("role")),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }
}
