package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.eka.auth.internal.service.RegistrationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eka.auth.registration.enabled=true")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class RegistrationHttpTests {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static final String PASSWORD = "Registration-test-password";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry metrics;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestSigningKeys.configure(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM auth.accounts").update();
        jdbc.sql("DELETE FROM auth.tenants").update();
    }

    @Test
    void anonymousRegistrationCreatesOneAdminAndCanLoginWithoutLeakingSecrets(CapturedOutput output)
            throws Exception {
        var response =
                post("/api/auth/register", body(" Team-A ", " ADMIN@Example.Test ", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var account = json.readValue(response.body(), RegistrationService.RegisteredAccount.class);
        assertThat(account.tenantSlug()).isEqualTo("team-a");
        assertThat(account.email()).isEqualTo("admin@example.test");
        assertThat(account.role()).isEqualTo("ADMIN");
        assertThat(response.body())
                .doesNotContain(PASSWORD, "password", "accessToken", "refreshToken", "hash");
        String hash =
                jdbc.sql("SELECT password_hash FROM auth.accounts WHERE id = ?")
                        .param(account.userId())
                        .query(String.class)
                        .single();
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        String token = login("team-a", "admin@example.test");
        var me = get("/api/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body())
                .contains(account.userId().toString(), account.tenantId().toString(), "ADMIN");
        assertThat(output.getAll()).doesNotContain(PASSWORD, hash, token);
        assertThat(
                        metrics.find("eka.auth.events")
                                .tag("outcome", "registration_succeeded")
                                .counter())
                .isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM auth.tenants").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM auth.accounts").query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void duplicateNormalizedSlugCannotOverwriteExistingAdmin() throws Exception {
        var first = post("/api/auth/register", body("team", "admin@example.test", PASSWORD));
        assertThat(first.statusCode()).isEqualTo(201);
        var before = jdbc.sql("SELECT * FROM auth.accounts").query().singleRow();
        var duplicate =
                post(
                        "/api/auth/register",
                        body(" TEAM ", "attacker@example.test", "Different-test-password"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).isEqualTo("{\"error\":\"registration_conflict\"}");
        assertThat(jdbc.sql("SELECT * FROM auth.accounts").query().singleRow()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM auth.tenants").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(get("/api/me", login("team", "admin@example.test")).statusCode()).isEqualTo(200);
    }

    @Test
    void sameEmailCanRegisterDistinctTenantsAndCannotReadOtherTenant() throws Exception {
        var a =
                json.readValue(
                        post("/api/auth/register", body("team-a", "same@example.test", PASSWORD))
                                .body(),
                        RegistrationService.RegisteredAccount.class);
        var b =
                json.readValue(
                        post("/api/auth/register", body("team-b", "same@example.test", PASSWORD))
                                .body(),
                        RegistrationService.RegisteredAccount.class);
        assertThat(a.tenantId()).isNotEqualTo(b.tenantId());
        String tokenA = login("team-a", "same@example.test");
        String tokenB = login("team-b", "same@example.test");
        assertThat(get("/api/tenant/members", tokenA).body())
                .contains(a.userId().toString())
                .doesNotContain(b.userId().toString());
        assertThat(get("/api/tenant/members", tokenB).body())
                .contains(b.userId().toString())
                .doesNotContain(a.userId().toString());
        assertThat(get("/api/tenant/members/" + b.userId(), tokenA).statusCode()).isEqualTo(404);
        assertThat(get("/api/tenant/members/" + a.userId(), tokenB).statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentDuplicateRegistrationCreatesExactlyOneTenantAndAdmin() throws Exception {
        String request = body("race", "same@example.test", PASSWORD);
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pending.add(
                    HTTP.sendAsync(
                            request("/api/auth/register", request),
                            HttpResponse.BodyHandlers.ofString()));
        }
        var statuses =
                pending.stream()
                        .map(CompletableFuture::join)
                        .map(HttpResponse::statusCode)
                        .toList();
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM auth.tenants").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM auth.accounts WHERE role = 'ADMIN'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1L);
    }

    @Test
    void invalidOrMalformedInputReturnsSafe400WithoutCreatingTenant(CapturedOutput output)
            throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{",
                        "{\"tenantSlug\":\"team\",\"password\":\"private-value\"}",
                        body("bad_slug", "admin@example.test", PASSWORD),
                        body("a".repeat(64), "admin@example.test", PASSWORD),
                        body("team", "invalid", PASSWORD),
                        body("team", "admin@example.test", "short"),
                        body("team", "admin@example.test", "a".repeat(73)),
                        body("team", "admin@example.test", "é".repeat(37)))) {
            var response = post("/api/auth/register", body);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM auth.tenants").query(Long.class).single())
                .isZero();
        assertThat(output.getAll()).doesNotContain(PASSWORD, "private-value");
    }

    @Test
    void callerCannotChooseExistingTenantUserIdOrRole() throws Exception {
        var original =
                json.readValue(
                        post("/api/auth/register", body("original", "admin@example.test", PASSWORD))
                                .body(),
                        RegistrationService.RegisteredAccount.class);
        UUID requestedUser = UUID.randomUUID();
        String forged =
                json.writeValueAsString(
                        Map.of(
                                "tenantSlug",
                                "new-team",
                                "email",
                                "new@example.test",
                                "password",
                                PASSWORD,
                                "tenantId",
                                original.tenantId(),
                                "userId",
                                requestedUser,
                                "role",
                                "VIEWER"));
        var result = post("/api/auth/register", forged);
        assertThat(result.statusCode()).isEqualTo(201);
        var created = json.readValue(result.body(), RegistrationService.RegisteredAccount.class);
        assertThat(created.role()).isEqualTo("ADMIN");
        assertThat(created.tenantId()).isNotEqualTo(original.tenantId());
        assertThat(created.userId()).isNotEqualTo(requestedUser);
        assertThat(
                        get(
                                        "/api/tenant/members/" + original.userId(),
                                        login("new-team", "new@example.test"))
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void accountInsertFailureRollsBackTenantAndDoesNotReportSuccess() throws Exception {
        jdbc.sql(
                        "ALTER TABLE auth.accounts ADD CONSTRAINT test_reject_email CHECK (email <> 'blocked@example.test')")
                .update();
        double before = successCount();
        try {
            var response =
                    post("/api/auth/register", body("rollback", "blocked@example.test", PASSWORD));
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).isEqualTo("{\"error\":\"registration_unavailable\"}");
            assertThat(
                            jdbc.sql("SELECT count(*) FROM auth.tenants WHERE slug = 'rollback'")
                                    .query(Long.class)
                                    .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM auth.accounts").query(Long.class).single())
                    .isZero();
            assertThat(successCount()).isEqualTo(before);
        } finally {
            jdbc.sql("ALTER TABLE auth.accounts DROP CONSTRAINT test_reject_email").update();
        }
        assertThat(
                        post("/api/auth/register", body("rollback", "admin@example.test", PASSWORD))
                                .statusCode())
                .isEqualTo(201);
    }

    private double successCount() {
        var counter =
                metrics.find("eka.auth.events").tag("outcome", "registration_succeeded").counter();
        return counter == null ? 0 : counter.count();
    }

    private String body(String slug, String email, String password) {
        return json.writeValueAsString(
                Map.of("tenantSlug", slug, "email", email, "password", password));
    }

    private HttpRequest request(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String slug, String email) throws Exception {
        var result = post("/api/auth/login", body(slug, email, PASSWORD));
        assertThat(result.statusCode()).isEqualTo(200);
        return json.readTree(result.body()).get("accessToken").asText();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
