package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.auth.internal.persistence.SessionRecord;
import com.eka.auth.internal.persistence.SessionRepository;
import com.eka.auth.internal.security.LoginService;
import com.eka.auth.internal.security.RefreshTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(AuthHttpTests.TimeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SessionHttpTests {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Session-test-password";
    private static final Instant NOW = AuthHttpTests.NOW;

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired AuthHttpTests.MutableClock clock;
    @Autowired SessionRepository sessions;
    UUID tenant;
    UUID user;
    UUID otherTenant;
    UUID otherUser;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestSigningKeys.configure(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void seed() {
        clock.now = NOW;
        jdbc.sql("DELETE FROM auth.accounts").update();
        jdbc.sql("DELETE FROM auth.tenants").update();
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        user = UUID.randomUUID();
        otherUser = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, 'alpha'), (?, 'beta')")
                .param(tenant)
                .param(otherTenant)
                .update();
        for (var ids : List.of(List.of(user, tenant), List.of(otherUser, otherTenant))) {
            jdbc.sql(
                            "INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role) VALUES (?, ?, 'admin@example.test', ?, 'ADMIN')")
                    .param(ids.get(0))
                    .param(ids.get(1))
                    .param(passwords.encode(PASSWORD))
                    .update();
        }
    }

    @Test
    void rotatesSingleUseSecretsWithoutSlidingExpiryOrStoringRawTokens(CapturedOutput output)
            throws Exception {
        var first = login("alpha");
        assertThat(first.expiresIn()).isEqualTo(900);
        assertThat(first.refreshExpiresIn()).isEqualTo(604800);
        clock.now = NOW.plusSeconds(60);
        var response = refresh(first.refreshToken());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var next = pair(response);
        assertThat(next.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(next.refreshExpiresIn()).isEqualTo(604740);
        assertThat(get("/api/me", next.accessToken()).statusCode()).isEqualTo(200);
        assertThat(get("/api/me", first.accessToken()).statusCode()).isEqualTo(200);
        var hashes =
                jdbc.sql("SELECT token_hash FROM auth.refresh_tokens").query(String.class).list();
        assertThat(hashes)
                .containsExactlyInAnyOrder(
                        RefreshTokens.hash(first.refreshToken()).orElseThrow(),
                        RefreshTokens.hash(next.refreshToken()).orElseThrow());
        assertThat(output.getAll())
                .doesNotContain(
                        first.refreshToken(), next.refreshToken(), first.accessToken(), PASSWORD);
    }

    @Test
    void replayRevocationCommitsAndDeniesWholeFamilyButNotAnotherSession() throws Exception {
        var first = login("alpha");
        var independent = login("alpha");
        var next = pair(refresh(first.refreshToken()));
        denied(refresh(first.refreshToken()));
        denied(get("/api/me", first.accessToken()));
        denied(get("/api/me", next.accessToken()));
        denied(refresh(next.refreshToken()));
        assertThat(get("/api/me", independent.accessToken()).statusCode()).isEqualTo(200);
        assertThat(refresh(independent.refreshToken()).statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM auth.sessions WHERE revoked_at IS NOT NULL")
                                .query(Long.class)
                                .single())
                .isEqualTo(1L);
    }

    @Test
    void logoutUsesAuthenticatedSessionAndImmediatelyDeniesAccessAndRefresh() throws Exception {
        var first = login("alpha");
        var other = login("beta");
        UUID otherSession =
                UUID.fromString(
                        SignedJWT.parse(other.accessToken())
                                .getJWTClaimsSet()
                                .getStringClaim("sid"));
        var response =
                post(
                        "/api/auth/logout",
                        Map.of("sid", otherSession, "tenantId", otherTenant),
                        first.accessToken());
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        denied(get("/api/me", first.accessToken()));
        denied(refresh(first.refreshToken()));
        denied(post("/api/auth/logout", Map.of(), first.accessToken()));
        denied(post("/api/auth/logout", Map.of(), null));
        assertThat(get("/api/me", other.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void absoluteSevenDayBoundaryCapsLastAccessTokenAndCannotBeExtended() throws Exception {
        var first = login("alpha");
        clock.now = NOW.plusSeconds(604799);
        var last = pair(refresh(first.refreshToken()));
        assertThat(last.expiresIn()).isEqualTo(1);
        assertThat(last.refreshExpiresIn()).isEqualTo(1);
        assertThat(get("/api/me", last.accessToken()).statusCode()).isEqualTo(200);
        clock.now = NOW.plusSeconds(604800);
        denied(get("/api/me", last.accessToken()));
        denied(refresh(last.refreshToken()));
        denied(refresh(first.refreshToken()));
    }

    @Test
    void refreshAfterAccessExpiryUsesCurrentRoleAndActiveIdentity() throws Exception {
        var first = login("alpha");
        clock.now = NOW.plusSeconds(901);
        denied(get("/api/me", first.accessToken()));
        jdbc.sql("UPDATE auth.accounts SET role = 'VIEWER' WHERE id = ?").param(user).update();
        var next = pair(refresh(first.refreshToken()));
        assertThat(get("/api/me", next.accessToken()).body()).contains("VIEWER");
        assertThat(get("/api/tenant/members", next.accessToken()).statusCode()).isEqualTo(403);
        jdbc.sql("UPDATE auth.accounts SET active = false WHERE id = ?").param(user).update();
        denied(refresh(next.refreshToken()));
        denied(get("/api/me", next.accessToken()));
        jdbc.sql("UPDATE auth.accounts SET active = true WHERE id = ?").param(user).update();
        denied(refresh(next.refreshToken()));
    }

    @Test
    void inactiveTenantAndDeletedAccountCannotRefresh() throws Exception {
        var first = login("alpha");
        jdbc.sql("UPDATE auth.tenants SET active = false WHERE id = ?").param(tenant).update();
        denied(refresh(first.refreshToken()));
        denied(get("/api/me", first.accessToken()));
        jdbc.sql("UPDATE auth.tenants SET active = true WHERE id = ?").param(tenant).update();
        var second = login("alpha");
        jdbc.sql("DELETE FROM auth.accounts WHERE id = ?").param(user).update();
        denied(refresh(second.refreshToken()));
        denied(get("/api/me", second.accessToken()));
    }

    @Test
    void concurrentRefreshHasOneWinnerThenReplayRevokesReturnedPair() throws Exception {
        var first = login("alpha");
        String body = json.writeValueAsString(Map.of("refreshToken", first.refreshToken()));
        var a =
                HTTP.sendAsync(
                        request("/api/auth/refresh", body, null),
                        HttpResponse.BodyHandlers.ofString());
        var b =
                HTTP.sendAsync(
                        request("/api/auth/refresh", body, null),
                        HttpResponse.BodyHandlers.ofString());
        var responses = List.of(a.join(), b.join());
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 401);
        var winner =
                pair(
                        responses.stream()
                                .filter(response -> response.statusCode() == 200)
                                .findFirst()
                                .orElseThrow());
        denied(get("/api/me", winner.accessToken()));
        denied(refresh(winner.refreshToken()));
        assertThat(jdbc.sql("SELECT count(*) FROM auth.refresh_tokens").query(Long.class).single())
                .isEqualTo(2L);
    }

    @Test
    void invalidUnknownAndLegacyTokensNeverRevealSecretsOrSelectAnotherTenant(CapturedOutput output)
            throws Exception {
        String unknown = RefreshTokens.create();
        denied(refresh(unknown));
        for (String body : List.of("{}", "{", "{\"refreshToken\":\"private-secret\"}")) {
            var response =
                    HTTP.send(
                            request("/api/auth/refresh", body, null),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        }
        var first = login("alpha");
        var other = login("beta");
        var forged =
                new JWTClaimsSet.Builder(SignedJWT.parse(first.accessToken()).getJWTClaimsSet())
                        .claim(
                                "sid",
                                SignedJWT.parse(other.accessToken())
                                        .getJWTClaimsSet()
                                        .getStringClaim("sid"))
                        .build();
        denied(get("/api/me", signed(forged)));
        var legacy =
                new JWTClaimsSet.Builder(SignedJWT.parse(first.accessToken()).getJWTClaimsSet())
                        .claim("sid", null)
                        .build();
        denied(get("/api/me", signed(legacy)));
        assertThat(get("/api/me", first.accessToken()).statusCode()).isEqualTo(200);
        assertThat(get("/api/tenant/members/" + otherUser, first.accessToken()).statusCode())
                .isEqualTo(404);
        assertThat(output.getAll())
                .doesNotContain(
                        unknown, "private-secret", first.refreshToken(), first.accessToken());
    }

    @Test
    void signerFailureRollsBackNewSessionAndRotationWithoutConsumingToken() throws Exception {
        var first = login("alpha");
        String digest = RefreshTokens.hash(first.refreshToken()).orElseThrow();
        String next = RefreshTokens.hash(RefreshTokens.create()).orElseThrow();
        var candidate = new SessionRecord(UUID.randomUUID(), tenant, user, NOW.plusSeconds(604800));
        assertThatThrownBy(
                        () ->
                                sessions.create(
                                        candidate,
                                        next,
                                        NOW,
                                        ignored -> {
                                            throw new IllegalStateException("test signer failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                sessions.rotate(
                                        digest,
                                        next,
                                        NOW,
                                        ignored -> {
                                            throw new IllegalStateException("test signer failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM auth.sessions").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM auth.refresh_tokens WHERE consumed_at IS NOT NULL")
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(refresh(first.refreshToken()).statusCode()).isEqualTo(200);
        assertThat(
                        sessions.create(
                                new SessionRecord(
                                        UUID.randomUUID(),
                                        otherTenant,
                                        user,
                                        NOW.plusSeconds(604800)),
                                next,
                                NOW,
                                ignored -> "not-issued"))
                .isEmpty();
    }

    @Test
    void concurrentLogoutAndRefreshCannotLeaveALiveReplacement() throws Exception {
        var first = login("alpha");
        var rotating =
                HTTP.sendAsync(
                        request(
                                "/api/auth/refresh",
                                json.writeValueAsString(
                                        Map.of("refreshToken", first.refreshToken())),
                                null),
                        HttpResponse.BodyHandlers.ofString());
        var loggingOut =
                HTTP.sendAsync(
                        request("/api/auth/logout", "{}", first.accessToken()),
                        HttpResponse.BodyHandlers.ofString());
        var rotated = rotating.join();
        assertThat(loggingOut.join().statusCode()).isEqualTo(204);
        assertThat(rotated.statusCode()).isIn(200, 401);
        if (rotated.statusCode() == 200) {
            var next = pair(rotated);
            denied(get("/api/me", next.accessToken()));
            denied(refresh(next.refreshToken()));
        }
        denied(get("/api/me", first.accessToken()));
        denied(refresh(first.refreshToken()));
    }

    private LoginService.TokenResponse login(String slug) throws Exception {
        var result =
                post(
                        "/api/auth/login",
                        Map.of(
                                "tenantSlug",
                                slug,
                                "email",
                                "admin@example.test",
                                "password",
                                PASSWORD),
                        null);
        assertThat(result.statusCode()).isEqualTo(200);
        return pair(result);
    }

    private LoginService.TokenResponse pair(HttpResponse<String> result) {
        assertThat(result.statusCode()).isEqualTo(200);
        return json.readValue(result.body(), LoginService.TokenResponse.class);
    }

    private HttpResponse<String> refresh(String token) throws Exception {
        return post("/api/auth/refresh", Map.of("refreshToken", token), null);
    }

    private HttpResponse<String> post(String path, Map<String, ?> body, String access)
            throws Exception {
        return HTTP.send(
                request(path, json.writeValueAsString(body), access),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String body, String access) {
        var builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json");
        if (access != null) {
            builder.header("Authorization", "Bearer " + access);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> get(String path, String access) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + access)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void denied(HttpResponse<String> result) {
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    }

    private String signed(JWTClaimsSet claims) throws Exception {
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner((RSAPrivateKey) TestSigningKeys.PAIR.getPrivate()));
        return token.serialize();
    }
}
