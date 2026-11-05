package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.eka.auth.api.CurrentAccount;
import com.eka.auth.internal.security.DevAccountProvisioner;
import com.eka.auth.internal.security.LoginService;
import com.eka.auth.internal.service.IdentityService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(AuthHttpTests.TimeConfiguration.class)
class AuthHttpTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static final String PASSWORD = "Test-only-long-password";
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired LoginService login;
    @Autowired MeterRegistry metrics;
    @Autowired MutableClock clock;
    @Autowired ObjectMapper json;
    UUID tenant;
    UUID otherTenant;
    UUID user;
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
        tenant = tenant("alpha");
        otherTenant = tenant("beta");
        user = account(tenant, "person@example.test", "VIEWER");
        otherUser = account(otherTenant, "person@example.test", "ADMIN");
    }

    @Test
    void loginReturnsBearerWithFifteenMinuteExpiryAndNoCache(CapturedOutput output)
            throws Exception {
        var result =
                post(
                        "{\"tenantSlug\":\"alpha\",\"email\":\"person@example.test\","
                                + "\"password\":\""
                                + PASSWORD
                                + "\"}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body())
                .contains("\"tokenType\":\"Bearer\"", "\"expiresIn\":900")
                .doesNotContain(PASSWORD, "passwordHash");
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        var response = login.login("alpha", "PERSON@example.test", PASSWORD);
        var jwt = SignedJWT.parse(response.accessToken());
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(NOW.plusSeconds(900));
        assertThat(response.toString()).doesNotContain(response.accessToken());
        assertThat(output.getAll()).doesNotContain(PASSWORD, response.accessToken());
        assertThat(
                        metrics.get("eka.auth.events")
                                .tag("outcome", "login_succeeded")
                                .counter()
                                .count())
                .isPositive();
        assertThat(get("/api/test/principal", response.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void badPasswordUnknownTenantAndInactiveIdentityHaveSameError() throws Exception {
        String badPassword = post(body("alpha", "person@example.test", "wrong")).body();
        assertThat(badPassword).isEqualTo("{\"error\":\"unauthorized\"}");
        for (String tenantSlug : new String[] {"missing", "beta"}) {
            assertThat(post(body(tenantSlug, "missing@example.test", PASSWORD)).statusCode())
                    .isEqualTo(401);
            assertThat(post(body(tenantSlug, "missing@example.test", PASSWORD)).body())
                    .isEqualTo(badPassword);
        }
        jdbc.sql("UPDATE auth.accounts SET active = false WHERE id = ?").param(user).update();
        assertThat(post(body("alpha", "person@example.test", PASSWORD)).body())
                .isEqualTo(badPassword);
        jdbc.sql("UPDATE auth.accounts SET active = true WHERE id = ?").param(user).update();
        jdbc.sql("UPDATE auth.tenants SET active = false WHERE id = ?").param(tenant).update();
        assertThat(post(body("alpha", "person@example.test", PASSWORD)).statusCode())
                .isEqualTo(401);
    }

    @Test
    void malformedLoginDoesNotEchoSecrets() throws Exception {
        for (String json :
                List.of(
                        "{",
                        "{}",
                        body("alpha", "not-an-email", PASSWORD),
                        body("alpha", "person@example.test", "x".repeat(73)),
                        body("alpha", "person@example.test", "é".repeat(40)))) {
            var response = post(json);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        }
    }

    @Test
    void healthIsPublicAndActuatorRequiresCurrentAdminRole() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info", null).statusCode()).isEqualTo(401);
        String token = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        assertThat(get("/actuator/info", token).statusCode()).isEqualTo(403);
        jdbc.sql("UPDATE auth.accounts SET role = 'ADMIN' WHERE id = ?").param(user).update();
        assertThat(get("/actuator/info", token).statusCode()).isEqualTo(200);
        jdbc.sql("UPDATE auth.accounts SET role = 'VIEWER' WHERE id = ?").param(user).update();
        assertThat(get("/actuator/info", token).statusCode()).isEqualTo(403);
    }

    @Test
    void missingMalformedTamperedAndExpiredTokensReturnJson401() throws Exception {
        denied(null);
        denied("not-a-jwt");
        String token = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        String[] pieces = token.split("\\.");
        char first = pieces[2].charAt(0) == 'A' ? 'B' : 'A';
        denied(pieces[0] + "." + pieces[1] + "." + first + pieces[2].substring(1));
        clock.now = NOW.plusSeconds(901);
        denied(token);
    }

    @Test
    void wrongKeyAlgorithmIssuerAudienceAndMissingClaimsAreDenied() throws Exception {
        denied(signed(TestSigningKeys.generate(), JWSAlgorithm.RS256, claims().build()));
        denied(signed(TestSigningKeys.PAIR, JWSAlgorithm.RS512, claims().build()));
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().issuer("https://other.test").build()));
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().audience("other").build()));
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().claim("tenant_id", null).build()));
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().subject("not-a-uuid").build()));
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().expirationTime(null).build()));
        denied(signed(TestSigningKeys.PAIR, JWSAlgorithm.RS256, claims().issueTime(null).build()));
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().notBeforeTime(Date.from(NOW.plusSeconds(60))).build()));
    }

    @Test
    void signedTenantSubstitutionAndDatabaseIdentityChangesAreDenied() throws Exception {
        denied(
                signed(
                        TestSigningKeys.PAIR,
                        JWSAlgorithm.RS256,
                        claims().claim("tenant_id", otherTenant.toString()).build()));
        String token = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        jdbc.sql("UPDATE auth.accounts SET active = false WHERE id = ?").param(user).update();
        denied(token);
        jdbc.sql("UPDATE auth.accounts SET active = true WHERE id = ?").param(user).update();
        jdbc.sql("UPDATE auth.tenants SET active = false WHERE id = ?").param(tenant).update();
        denied(token);
        jdbc.sql("UPDATE auth.tenants SET active = true WHERE id = ?").param(tenant).update();
        jdbc.sql("DELETE FROM auth.accounts WHERE id = ?").param(user).update();
        denied(token);
    }

    @Test
    void requestsResolveIndependentTenantAndIgnoreForgedHeader() throws Exception {
        String a = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        String b = login.login("beta", "person@example.test", PASSWORD).accessToken();
        for (String token : List.of(a, b, a)) {
            UUID expected = token.equals(a) ? tenant : otherTenant;
            var response =
                    HTTP.send(
                            HttpRequest.newBuilder(uri("/api/test/principal"))
                                    .header("Authorization", "Bearer " + token)
                                    .header("X-Tenant-Id", UUID.randomUUID().toString())
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(expected.toString());
        }
        denied(null);
    }

    @Test
    void devProvisioningHashesAndNeverOverwritesExistingAccounts() {
        var env =
                new MockEnvironment()
                        .withProperty("eka.auth.bootstrap.tenant-slug", "bootstrap")
                        .withProperty("eka.auth.bootstrap.email", "DEV@example.test")
                        .withProperty("eka.auth.bootstrap.password", PASSWORD)
                        .withProperty("eka.auth.bootstrap.role", "ADMIN");
        var provisioner = new DevAccountProvisioner(jdbc, passwords, env);
        provisioner.run(null);
        String hash =
                jdbc.sql("SELECT password_hash FROM auth.accounts WHERE email = 'dev@example.test'")
                        .query(String.class)
                        .single();
        assertThat(hash).doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        env.setProperty("eka.auth.bootstrap.password", "Changed-test-password");
        provisioner.run(null);
        assertThat(
                        jdbc.sql(
                                        "SELECT password_hash FROM auth.accounts WHERE email = 'dev@example.test'")
                                .query(String.class)
                                .single())
                .isEqualTo(hash);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM auth.accounts WHERE email = 'dev@example.test'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1L);
    }

    @Test
    void meReturnsOnlySafeCurrentIdentityForEitherTenant() throws Exception {
        String a = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        String b = login.login("beta", "person@example.test", PASSWORD).accessToken();
        var responseA = get("/api/me", a);
        var responseB = get("/api/me", b);
        assertThat(responseA.statusCode()).isEqualTo(200);
        assertThat(responseB.statusCode()).isEqualTo(200);
        assertThat(json.readValue(responseA.body(), IdentityService.MeView.class))
                .isEqualTo(
                        new IdentityService.MeView(user, tenant, "person@example.test", "VIEWER"));
        assertThat(json.readValue(responseB.body(), IdentityService.MeView.class))
                .isEqualTo(
                        new IdentityService.MeView(
                                otherUser, otherTenant, "person@example.test", "ADMIN"));
        assertThat(responseA.body())
                .doesNotContain("password", "hash", "accessToken", otherTenant.toString());
        assertThat(responseA.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void onlyAdminsCanReadTheirOwnTenantMemberListAndDetails() throws Exception {
        String a = adminToken();
        String b = login.login("beta", "person@example.test", PASSWORD).accessToken();
        for (String token : List.of(a, b)) {
            UUID expectedTenant = token.equals(a) ? tenant : otherTenant;
            UUID expectedUser = token.equals(a) ? user : otherUser;
            UUID foreignUser = token.equals(a) ? otherUser : user;
            var listed = get("/api/tenant/members", token);
            assertThat(listed.statusCode()).isEqualTo(200);
            var page = json.readValue(listed.body(), IdentityService.MemberPage.class);
            assertThat(page.page()).isZero();
            assertThat(page.size()).isEqualTo(20);
            assertThat(page.items())
                    .extracting(IdentityService.MemberView::id)
                    .containsExactly(expectedUser);
            assertThat(page.items()).allMatch(item -> item.tenantId().equals(expectedTenant));
            var detail = get("/api/tenant/members/" + expectedUser, token);
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(detail.body())
                    .doesNotContain("password", "hash", "accessToken", foreignUser.toString());
            assertThat(json.readValue(detail.body(), IdentityService.MemberView.class).id())
                    .isEqualTo(expectedUser);
            var foreign = get("/api/tenant/members/" + foreignUser, token);
            var missing = get("/api/tenant/members/" + UUID.randomUUID(), token);
            assertThat(foreign.statusCode()).isEqualTo(404);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(foreign.body())
                    .isEqualTo(missing.body())
                    .isEqualTo("{\"error\":\"not_found\"}");
        }
        assertThat(
                        metrics.get("eka.auth.events")
                                .tag("outcome", "member_not_found")
                                .counter()
                                .count())
                .isPositive();
    }

    @Test
    void memberGets403EvenWithForgedRoleTenantAndInvalidMemberId() throws Exception {
        String member = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        for (String path :
                List.of(
                        "/api/tenant/members",
                        "/api/tenant/members/" + user,
                        "/api/tenant/members/" + otherUser,
                        "/api/tenant/members/not-a-uuid",
                        "/api/tenant/members?role=ADMIN&tenantId=" + otherTenant)) {
            var response = get(path, member);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).isEqualTo("{\"error\":\"forbidden\"}");
        }
    }

    @Test
    void anonymousAndExpiredIdentityCannotUseAnyRealIdentityEndpoint() throws Exception {
        for (String path :
                List.of("/api/me", "/api/tenant/members", "/api/tenant/members/" + user)) {
            assertThat(get(path, null).statusCode()).isEqualTo(401);
        }
        String token = adminToken();
        jdbc.sql("UPDATE auth.accounts SET active = false WHERE id = ?").param(user).update();
        assertThat(get("/api/me", token).statusCode()).isEqualTo(401);
        assertThat(get("/api/tenant/members", token).statusCode()).isEqualTo(401);
        jdbc.sql("UPDATE auth.accounts SET active = true WHERE id = ?").param(user).update();
        jdbc.sql("UPDATE auth.tenants SET active = false WHERE id = ?").param(tenant).update();
        assertThat(get("/api/me", token).statusCode()).isEqualTo(401);
        jdbc.sql("UPDATE auth.tenants SET active = true WHERE id = ?").param(tenant).update();
        clock.now = NOW.plusSeconds(901);
        assertThat(get("/api/tenant/members", token).statusCode()).isEqualTo(401);
    }

    @Test
    void paginationDefaultsBoundsAndOrderingAreTenantScoped() throws Exception {
        String token = adminToken();
        for (int index = 0; index < 21; index++) {
            account(tenant, "extra-" + index + "@example.test", "VIEWER");
        }
        var first =
                json.readValue(
                        get("/api/tenant/members", token).body(), IdentityService.MemberPage.class);
        var second =
                json.readValue(
                        get("/api/tenant/members?page=1", token).body(),
                        IdentityService.MemberPage.class);
        var repeated =
                json.readValue(
                        get("/api/tenant/members?page=0&size=20", token).body(),
                        IdentityService.MemberPage.class);
        assertThat(first.items()).hasSize(20).allMatch(item -> item.tenantId().equals(tenant));
        assertThat(second.items()).hasSize(2).allMatch(item -> item.tenantId().equals(tenant));
        assertThat(repeated).isEqualTo(first);
        var ids = new ArrayList<UUID>();
        first.items().forEach(item -> ids.add(item.id()));
        second.items().forEach(item -> ids.add(item.id()));
        assertThat(ids).hasSize(22).doesNotHaveDuplicates().doesNotContain(otherUser);
        var maximum = get("/api/tenant/members?size=100", token);
        assertThat(maximum.statusCode()).isEqualTo(200);
        assertThat(json.readValue(maximum.body(), IdentityService.MemberPage.class).items())
                .hasSize(22);
        assertThat(
                        json.readValue(
                                        get("/api/tenant/members?page=100", token).body(),
                                        IdentityService.MemberPage.class)
                                .items())
                .isEmpty();
    }

    @Test
    void malformedPaginationAndUuidReturnGeneric400ForAdmin() throws Exception {
        String token = adminToken();
        for (String suffix :
                List.of(
                        "?page=-1",
                        "?size=0",
                        "?size=101",
                        "?page=x",
                        "?page=2147483647&size=100",
                        "?page=2147483648",
                        "/not-a-uuid")) {
            var response = get("/api/tenant/members" + suffix, token);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        }
    }

    @Test
    void clientTenantAndRoleValuesCannotChangeAdminScope() throws Exception {
        String token = adminToken();
        var response =
                HTTP.send(
                        HttpRequest.newBuilder(
                                        uri(
                                                "/api/tenant/members?tenantId="
                                                        + otherTenant
                                                        + "&role=ADMIN"))
                                .header("Authorization", "Bearer " + token)
                                .header("X-Tenant-Id", otherTenant.toString())
                                .header("Content-Type", "application/json")
                                .method(
                                        "GET",
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"tenantId\":\""
                                                        + otherTenant
                                                        + "\",\"role\":\"ADMIN\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readValue(response.body(), IdentityService.MemberPage.class).items())
                .allMatch(item -> item.tenantId().equals(tenant));
        assertThat(response.body()).doesNotContain(otherTenant.toString(), otherUser.toString());
    }

    @Test
    void interleavedConcurrentRequestsRetainIndependentTenantPrincipals() throws Exception {
        String a = adminToken();
        String b = login.login("beta", "person@example.test", PASSWORD).accessToken();
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        var expected = new ArrayList<UUID>();
        for (int index = 0; index < 12; index++) {
            String token = index % 2 == 0 ? a : b;
            expected.add(index % 2 == 0 ? tenant : otherTenant);
            pending.add(
                    HTTP.sendAsync(
                            HttpRequest.newBuilder(uri("/api/tenant/members"))
                                    .header("Authorization", "Bearer " + token)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()));
        }
        for (int index = 0; index < pending.size(); index++) {
            var response = pending.get(index).join();
            assertThat(response.statusCode()).isEqualTo(200);
            UUID tenantId = expected.get(index);
            assertThat(json.readValue(response.body(), IdentityService.MemberPage.class).items())
                    .hasSize(1)
                    .allMatch(item -> item.tenantId().equals(tenantId));
        }
        assertThat(get("/api/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    void roleChangesAffectMemberRoutesWithoutReissuingToken() throws Exception {
        String token = adminToken();
        assertThat(get("/api/tenant/members", token).statusCode()).isEqualTo(200);
        jdbc.sql("UPDATE auth.accounts SET role = 'VIEWER' WHERE id = ?").param(user).update();
        assertThat(get("/api/tenant/members", token).statusCode()).isEqualTo(403);
        assertThat(get("/api/tenant/members/" + user, token).statusCode()).isEqualTo(403);
        assertThat(
                        json.readValue(get("/api/me", token).body(), IdentityService.MeView.class)
                                .role())
                .isEqualTo("VIEWER");
    }

    @Test
    void currentDatabaseRoleControlsIdentityAndAdminRoutesForSameToken() throws Exception {
        String token = login.login("alpha", "person@example.test", PASSWORD).accessToken();
        for (String role : new String[] {"ADMIN", "EDITOR", "VIEWER"}) {
            jdbc.sql("UPDATE auth.accounts SET role = ? WHERE id = ?")
                    .param(role)
                    .param(user)
                    .update();
            var me = get("/api/me", token);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readValue(me.body(), IdentityService.MeView.class).role())
                    .isEqualTo(role);
            int expected = role.equals("ADMIN") ? 200 : 403;
            assertThat(get("/api/tenant/members", token).statusCode()).isEqualTo(expected);
            assertThat(get("/api/tenant/members/" + user, token).statusCode()).isEqualTo(expected);
            assertThat(get("/actuator/info", token).statusCode()).isEqualTo(expected);
            assertThat(get("/api/tenant/members/" + otherUser, token).statusCode())
                    .isEqualTo(role.equals("ADMIN") ? 404 : 403);
            if (!role.equals("ADMIN")) {
                assertThat(get("/api/tenant/members/not-a-uuid?role=ADMIN", token).statusCode())
                        .isEqualTo(403);
            }
        }
    }

    @Test
    void devBootstrapSupportsAllCurrentRolesAndRejectsMemberBeforeWriting() throws Exception {
        var env =
                new MockEnvironment()
                        .withProperty("eka.auth.bootstrap.tenant-slug", "roles-bootstrap")
                        .withProperty("eka.auth.bootstrap.password", PASSWORD);
        var provisioner = new DevAccountProvisioner(jdbc, passwords, env);
        for (String role : new String[] {"ADMIN", "EDITOR", "VIEWER"}) {
            String email = role.toLowerCase(java.util.Locale.ROOT) + "@example.test";
            env.setProperty("eka.auth.bootstrap.email", email);
            env.setProperty("eka.auth.bootstrap.role", role);
            provisioner.run(null);
            var token = login.login("roles-bootstrap", email, PASSWORD).accessToken();
            assertThat(
                            json.readValue(
                                            get("/api/me", token).body(),
                                            IdentityService.MeView.class)
                                    .role())
                    .isEqualTo(role);
        }
        env.setProperty("eka.auth.bootstrap.role", "MEMBER");
        env.setProperty("eka.auth.bootstrap.tenant-slug", "must-not-exist");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provisioner.run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM auth.tenants WHERE slug = 'must-not-exist'")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private String adminToken() {
        jdbc.sql("UPDATE auth.accounts SET role = 'ADMIN' WHERE id = ?").param(user).update();
        return login.login("alpha", "person@example.test", PASSWORD).accessToken();
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject(user.toString())
                .claim("tenant_id", tenant.toString())
                .issuer(TestSigningKeys.ISSUER)
                .audience(TestSigningKeys.AUDIENCE)
                .issueTime(Date.from(NOW))
                .notBeforeTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(900)));
    }

    private String signed(KeyPair keys, JWSAlgorithm algorithm, JWTClaimsSet claims)
            throws Exception {
        var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private void denied(String token) throws Exception {
        var response = get("/api/test/principal", token);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    private HttpResponse<String> post(String body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String body(String slug, String email, String password) {
        return "{\"tenantSlug\":\""
                + slug
                + "\",\"email\":\""
                + email
                + "\",\"password\":\""
                + password
                + "\"}";
    }

    private UUID tenant(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, ?)")
                .param(id)
                .param(slug)
                .update();
        return id;
    }

    private UUID account(UUID tenant, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO auth.accounts (id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, ?)")
                .param(id)
                .param(tenant)
                .param(email)
                .param(passwords.encode(PASSWORD))
                .param(role)
                .update();
        return id;
    }

    @TestComponent
    @RestController
    static class TestEndpoints {
        private final CurrentAccount current;

        TestEndpoints(CurrentAccount current) {
            this.current = current;
        }

        @GetMapping("/api/test/principal")
        com.eka.auth.api.AuthenticatedAccount principal() {
            return current.require();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        TestEndpoints testEndpoints(CurrentAccount current) {
            return new TestEndpoints(current);
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
