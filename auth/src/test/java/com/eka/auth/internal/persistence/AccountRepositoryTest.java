package com.eka.auth.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AccountRepositoryTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String HASH = "{bcrypt}test-only-placeholder-not-a-login-credential";
    private static JdbcClient jdbc;
    private static Flyway flyway;
    private AccountRepository repository;
    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void migrate() {
        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(true).load();
        flyway.migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void fixtures() {
        jdbc.sql("DELETE FROM auth.accounts").update();
        jdbc.sql("DELETE FROM auth.tenants").update();
        tenantA = tenant("tenant-a");
        tenantB = tenant("tenant-b");
        repository = new AccountRepository(jdbc);
    }

    @Test
    void secondMigrationPreservesDataAndHistory() {
        UUID id = account(tenantA, "person@example.test", "ADMIN");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(3);
        assertThat(repository.findById(tenantA, id)).isPresent();
        assertThat(jdbc.sql("SELECT count(*) FROM auth.tenants").query(Long.class).single())
                .isEqualTo(2L);
    }

    @Test
    void accountLookupIsIsolatedInBothDirections() {
        UUID a = account(tenantA, "same@example.test", "ADMIN");
        UUID b = account(tenantB, "same@example.test", "VIEWER");
        assertThat(repository.findById(tenantA, a))
                .get()
                .satisfies(
                        value -> {
                            assertThat(value.tenantId()).isEqualTo(tenantA);
                            assertThat(value.role()).isEqualTo(AccountRole.ADMIN);
                            assertThat(value.createdAt()).isNotNull();
                        });
        assertThat(repository.findById(tenantB, b)).isPresent();
        assertThat(repository.findById(tenantA, b)).isEmpty();
        assertThat(repository.findActiveIdentity(tenantA, a)).isPresent();
        assertThat(repository.findActiveIdentity(tenantA, b)).isEmpty();
        assertThat(repository.findActiveIdentity(tenantB, a)).isEmpty();
        assertThat(repository.findById(tenantB, a)).isEmpty();
        assertThat(repository.findById(tenantA, UUID.randomUUID())).isEmpty();
    }

    @Test
    void paginatedListsNeverContainTheOtherTenant() {
        account(tenantA, "one@example.test", "ADMIN");
        account(tenantA, "two@example.test", "VIEWER");
        UUID b = account(tenantB, "one@example.test", "ADMIN");
        var first = repository.findMembers(tenantA, 1, 0);
        var second = repository.findMembers(tenantA, 1, 1);
        assertThat(first).hasSize(1).allMatch(value -> value.tenantId().equals(tenantA));
        assertThat(second).hasSize(1).allMatch(value -> value.tenantId().equals(tenantA));
        assertThat(first.getFirst().id()).isNotEqualTo(second.getFirst().id());
        assertThat(repository.findMembers(tenantA, 1, 0)).isEqualTo(first);
        assertThat(repository.findMembers(tenantA, 100, 2)).isEmpty();
        assertThat(repository.findMembers(tenantB, 100, 0))
                .extracting(AccountSummary::id)
                .containsExactly(b);
        assertThat(repository.findMembers(UUID.randomUUID(), 20, 0)).isEmpty();
    }

    @Test
    void credentialsAreScopedAndEmailIsNormalizedAtLookup() {
        UUID a = account(tenantA, "same@example.test", "ADMIN");
        UUID b = account(tenantB, "same@example.test", "VIEWER");
        var credentialA =
                repository.findCredentials("tenant-a", " SAME@EXAMPLE.TEST ").orElseThrow();
        assertThat(credentialA.account().id()).isEqualTo(a);
        assertThat(credentialA.passwordHash()).isEqualTo(HASH);
        assertThat(credentialA.tenantActive()).isTrue();
        assertThat(credentialA.toString()).doesNotContain(HASH, "same@example.test");
        assertThat(
                        repository
                                .findCredentials("tenant-b", "same@example.test")
                                .orElseThrow()
                                .account()
                                .id())
                .isEqualTo(b);
        assertThat(repository.findCredentials("missing", "same@example.test")).isEmpty();
        assertThat(repository.findCredentials("tenant-a' OR '1'='1", "same@example.test"))
                .isEmpty();
        assertThat(repository.findCredentials("tenant-a", "' OR '1'='1")).isEmpty();
    }

    @Test
    void inactiveFlagsAreAvailableForFutureAuthenticationChecks() {
        UUID id = account(tenantA, "inactive@example.test", "VIEWER");
        jdbc.sql("UPDATE auth.tenants SET active = false WHERE id = ?").param(tenantA).update();
        jdbc.sql("UPDATE auth.accounts SET active = false WHERE id = ?").param(id).update();
        var credentials =
                repository.findCredentials("tenant-a", "inactive@example.test").orElseThrow();
        assertThat(credentials.tenantActive()).isFalse();
        assertThat(repository.findActiveIdentity(tenantA, id)).isEmpty();
        assertThat(credentials.account().active()).isFalse();
    }

    @Test
    void duplicateSlugIsRejected() {
        assertThatThrownBy(() -> tenant("tenant-a"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailIsUniqueWithinTenantButCanRepeatAcrossTenants() {
        account(tenantA, "same@example.test", "VIEWER");
        account(tenantB, "same@example.test", "VIEWER");
        assertThatThrownBy(() -> account(tenantA, "same@example.test", "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidTenantReferenceAndRoleAreRejected() {
        assertThatThrownBy(() -> account(UUID.randomUUID(), "person@example.test", "VIEWER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> account(tenantA, "person@example.test", "SUPERADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonNormalizedOrBlankEmailIsRejectedByDatabase() {
        for (String email :
                new String[] {"UPPER@example.test", " person@example.test ", "", "invalid"}) {
            assertThatThrownBy(() -> account(tenantA, email, "VIEWER"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void invalidTenantSlugIsRejected() {
        assertThatThrownBy(() -> tenant("Tenant A"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiredFieldsAndNonemptyHashAreEnforcedWithoutPlaintextColumn() {
        assertThatThrownBy(() -> account(null, "person@example.test", "VIEWER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role)
                VALUES (?, ?, ?, '', 'VIEWER')
                """)
                                        .param(UUID.randomUUID())
                                        .param(tenantA)
                                        .param("empty@example.test")
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.sql(
                                        """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'auth' AND table_name = 'accounts'
                """)
                                .query(String.class)
                                .list())
                .contains("password_hash")
                .doesNotContain("password", "plaintext_password");
    }

    @Test
    void paginationAndTenantAreMandatory() {
        assertThatThrownBy(() -> repository.findMembers(tenantA, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findMembers(tenantA, 101, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findMembers(tenantA, 20, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findMembers(null, 20, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.findById(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void currentRolesAreAcceptedAndLegacyMemberIsRejected() {
        for (String role : new String[] {"ADMIN", "EDITOR", "VIEWER"}) {
            UUID id = account(tenantA, role.toLowerCase() + "@example.test", role);
            assertThat(repository.findById(tenantA, id).orElseThrow().role().name())
                    .isEqualTo(role);
        }
        assertThatThrownBy(() -> account(tenantA, "legacy@example.test", "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UUID tenant(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, ?)")
                .param(id)
                .param(slug)
                .update();
        return id;
    }

    private static UUID account(UUID tenantId, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id)
                .param(tenantId)
                .param(email)
                .param(HASH)
                .param(role)
                .update();
        return id;
    }
}
