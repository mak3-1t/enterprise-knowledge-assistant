package com.eka.auth.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ThreeRoleMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradesV1WithoutChangingIdentityCredentialsOrTenantAndCanRestart() {
        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var v1 = Flyway.configure().dataSource(dataSource).target("1").cleanDisabled(true).load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        var jdbc = JdbcClient.create(dataSource);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, 'upgrade-a'), (?, 'upgrade-b')")
                .param(a)
                .param(b)
                .update();
        UUID member = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        insert(jdbc, member, a, "MEMBER", false);
        insert(jdbc, admin, b, "ADMIN", true);
        var memberBefore = row(jdbc, member);
        var adminBefore = row(jdbc, admin);
        var tenantsBefore = jdbc.sql("SELECT * FROM auth.tenants ORDER BY id").query().listOfRows();

        var current = Flyway.configure().dataSource(dataSource).cleanDisabled(true).load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("2");
        Map<String, Object> expected = new HashMap<>(memberBefore);
        expected.put("role", "VIEWER");
        assertThat(row(jdbc, member)).isEqualTo(expected);
        assertThat(row(jdbc, admin)).isEqualTo(adminBefore);
        assertThat(jdbc.sql("SELECT * FROM auth.tenants ORDER BY id").query().listOfRows())
                .isEqualTo(tenantsBefore);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID(), a, "MEMBER", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String role : new String[] {"EDITOR", "VIEWER"}) {
            insert(jdbc, UUID.randomUUID(), b, role, true);
        }
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(row(jdbc, member)).isEqualTo(expected);
        assertThat(current.info().applied()).hasSize(2);
    }

    private static Map<String, Object> row(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT * FROM auth.accounts WHERE id = ?").param(id).query().singleRow();
    }

    private static void insert(JdbcClient jdbc, UUID id, UUID tenant, String role, boolean active) {
        jdbc.sql(
                        """
                INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role, active)
                VALUES (?, ?, ?, 'test-only-hash', ?, ?)
                """)
                .param(id)
                .param(tenant)
                .param(id + "@example.test")
                .param(role)
                .param(active)
                .update();
    }
}
