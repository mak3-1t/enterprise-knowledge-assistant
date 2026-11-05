package com.eka.auth.internal.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration is the only anonymous creation path; callers never supply tenant/user IDs or role.
 */
@Repository
public class RegistrationRepository {
    private final JdbcClient jdbc;

    public RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void create(UUID tenantId, String slug, UUID userId, String email, String hash) {
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, ?)")
                .param(tenantId)
                .param(slug)
                .update();
        jdbc.sql(
                        """
                INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role)
                VALUES (?, ?, ?, ?, 'ADMIN')
                """)
                .param(userId)
                .param(tenantId)
                .param(email)
                .param(hash)
                .update();
    }
}
