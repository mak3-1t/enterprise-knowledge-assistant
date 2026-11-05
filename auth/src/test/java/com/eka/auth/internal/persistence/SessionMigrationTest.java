package com.eka.auth.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
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
class SessionMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradesV2PreservingAccountsAndEnforcesSessionTenantOwnership() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("2").cleanDisabled(true).load().migrate();
        var jdbc = JdbcClient.create(source);
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, 'before-v3'), (?, 'other-v3')")
                .param(tenant)
                .param(other)
                .update();
        jdbc.sql(
                        "INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role, active) VALUES (?, ?, 'before@example.test', 'test-only-hash', 'VIEWER', false)")
                .param(user)
                .param(tenant)
                .update();
        var before = jdbc.sql("SELECT * FROM auth.accounts").query().singleRow();
        var latest = Flyway.configure().dataSource(source).cleanDisabled(true).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(jdbc.sql("SELECT * FROM auth.accounts").query().singleRow()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM auth.sessions").query(Long.class).single())
                .isZero();
        var now = Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC);
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "INSERT INTO auth.sessions (id, tenant_id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
                                        .param(UUID.randomUUID())
                                        .param(other)
                                        .param(user)
                                        .param(now)
                                        .param(now.plusDays(7))
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.sql(
                                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'auth' AND table_name = 'refresh_tokens'")
                                .query(String.class)
                                .list())
                .containsExactlyInAnyOrder("token_hash", "session_id", "created_at", "consumed_at");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(latest.info().applied()).hasSize(3);
    }
}
