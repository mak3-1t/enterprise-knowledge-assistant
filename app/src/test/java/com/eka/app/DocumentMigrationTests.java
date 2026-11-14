package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DocumentMigrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @Test
    void v3ToV12PreservesAuthAndExistingV4DocumentMetadata() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("3").cleanDisabled(true).load().migrate();
        var jdbc = JdbcClient.create(source);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, 'before-documents')")
                .param(tenant)
                .update();
        jdbc.sql(
                        "INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role) VALUES (?, ?, 'admin@example.test', 'test-only-hash', 'ADMIN')")
                .param(user)
                .param(tenant)
                .update();
        var now = Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC);
        jdbc.sql(
                        "INSERT INTO auth.sessions (id, tenant_id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
                .param(session)
                .param(tenant)
                .param(user)
                .param(now)
                .param(now.plusDays(7))
                .update();
        jdbc.sql(
                        "INSERT INTO auth.refresh_tokens (token_hash, session_id, created_at) VALUES (?, ?, ?)")
                .param("a".repeat(64))
                .param(session)
                .param(now)
                .update();
        var accountBefore = jdbc.sql("SELECT * FROM auth.accounts").query().singleRow();
        var sessionBefore = jdbc.sql("SELECT * FROM auth.sessions").query().singleRow();
        var refreshBefore = jdbc.sql("SELECT * FROM auth.refresh_tokens").query().singleRow();
        Flyway.configure().dataSource(source).target("4").cleanDisabled(true).load().migrate();
        UUID document = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO ingestion.documents (id, tenant_id, uploaded_by, filename, format, object_key,
                    storage_state, ingestion_status, byte_size, sha256, uploaded_at)
                VALUES (?, ?, ?, 'existing.txt', 'TXT', ?, 'STORED', 'UPLOADED', 5, ?, CURRENT_TIMESTAMP)
                """)
                .param(document)
                .param(tenant)
                .param(user)
                .param("tenants/" + tenant + "/documents/" + document + "/original")
                .param("a".repeat(64))
                .update();
        var documentBefore = jdbc.sql("SELECT * FROM ingestion.documents").query().singleRow();
        var latest = Flyway.configure().dataSource(source).cleanDisabled(true).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(jdbc.sql("SELECT * FROM auth.accounts").query().singleRow())
                .isEqualTo(accountBefore);
        assertThat(jdbc.sql("SELECT * FROM auth.sessions").query().singleRow())
                .isEqualTo(sessionBefore);
        assertThat(jdbc.sql("SELECT * FROM auth.refresh_tokens").query().singleRow())
                .isEqualTo(refreshBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion.documents").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT * FROM ingestion.documents").query().singleRow())
                .containsAllEntriesOf(documentBefore)
                .containsEntry("upload_status", "UPLOADED");
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
}
