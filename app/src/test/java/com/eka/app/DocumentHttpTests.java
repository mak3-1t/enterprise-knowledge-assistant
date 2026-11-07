package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.VerifiedUpload;
import com.eka.ingestion.internal.scan.DocumentScanWorker;
import com.eka.ingestion.internal.scan.InspectionRejected;
import com.eka.ingestion.internal.scan.ScanJobRepository;
import com.eka.ingestion.internal.service.UploadEvents;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DocumentHttpTests {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static final String PASSWORD = "Document-test-password";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired UploadRepository uploads;
    @Autowired ScanJobRepository jobs;
    @Autowired UploadEvents events;
    @MockitoBean UploadStorage storage;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestSigningKeys.configure(registry);
        registry.add("eka.auth.registration.enabled", () -> "true");
        registry.add("eka.upload.enabled", () -> "true");
        registry.add("eka.upload.access-key", () -> "test-access");
        registry.add("eka.upload.secret-key", () -> "test-secret");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM ingestion.document_scan_jobs").update();
        jdbc.sql("DELETE FROM ingestion.document_outbox").update();
        jdbc.sql("DELETE FROM ingestion.documents").update();
        jdbc.sql("DELETE FROM ingestion.upload_quotas").update();
        jdbc.sql("DELETE FROM auth.accounts").update();
        jdbc.sql("DELETE FROM auth.tenants").update();
        when(storage.authorize(any()))
                .thenReturn(
                        new UploadStorage.UploadForm(
                                URI.create("http://localhost:9000/private-test"),
                                Map.of("key", "signed-staging-only"),
                                Instant.now().plusSeconds(600)));
        when(storage.seal(any())).thenReturn(new VerifiedUpload(5, "a".repeat(64)));
    }

    @Test
    void scanHandoffAndMalwareVerdictsAreVisibleOverHttpWithoutPrematureReady() throws Exception {
        String token = account("scan-http");
        when(storage.seal(any()))
                .thenReturn(
                        new VerifiedUpload(
                                5,
                                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        when(storage.open(any()))
                .thenAnswer(
                        call ->
                                new java.io.ByteArrayInputStream(
                                        "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        UUID clean = id(init(token, "clean-scan-key"));
        send("POST", "/api/documents/" + clean + "/complete", token, null, Map.of());
        var cleanWorker =
                new DocumentScanWorker(
                        jobs, uploads, storage, file -> {}, (file, format) -> {}, events);
        cleanWorker.tick();
        assertThat(send("GET", "/api/documents/" + clean, token, null, null).body())
                .contains("PROCESSING");
        assertThat(
                        send("GET", "/api/documents/" + clean + "/download", token, null, null)
                                .statusCode())
                .isEqualTo(409);
        UUID bad = id(init(token, "bad-scan-key"));
        send("POST", "/api/documents/" + bad + "/complete", token, null, Map.of());
        var badWorker =
                new DocumentScanWorker(
                        jobs,
                        uploads,
                        storage,
                        file -> {
                            throw new InspectionRejected("MALWARE_DETECTED");
                        },
                        (file, format) -> {},
                        events);
        badWorker.tick();
        var rejected = send("GET", "/api/documents/" + bad, token, null, null);
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("REJECTED", "MALWARE_DETECTED");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM ingestion.document_outbox WHERE event_type = 'CONTENT_VERIFIED'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void realHttpRetriesCompleteOutboxAndSharedMetadataWithoutPrematureDownload() throws Exception {
        String token = account("docs-a");
        var first = init(token, "request-key");
        assertThat(first.statusCode()).isEqualTo(200);
        UUID id = id(first);
        assertThat(id(init(token, "request-key"))).isEqualTo(id);
        var changed =
                send(
                        "POST",
                        "/api/documents/uploads",
                        token,
                        "request-key",
                        Map.of("filename", "other.txt", "sizeBytes", 5));
        assertThat(changed.statusCode()).isEqualTo(409);
        String path = "/api/documents/" + id;
        assertThat(send("POST", path + "/complete", token, null, Map.of()).statusCode())
                .isEqualTo(200);
        assertThat(send("POST", path + "/complete", token, null, Map.of()).statusCode())
                .isEqualTo(200);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ingestion.document_outbox")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        var detail = send("GET", path, token, null, null);
        assertThat(detail.body())
                .contains("UPLOADED")
                .doesNotContain("objectKey", "sha256", "staging", "fingerprint");
        assertThat(detail.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(send("GET", path + "/download", token, null, null).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE auth.accounts SET role = 'VIEWER'").update();
        assertThat(send("GET", path, token, null, null).statusCode()).isEqualTo(200);
        assertThat(init(token, "viewer-key").statusCode()).isEqualTo(403);
        assertThat(send("POST", path + "/complete", token, null, Map.of()).statusCode())
                .isEqualTo(403);
        assertThat(send("DELETE", path, token, null, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", path + "/restore", token, null, Map.of()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void bothTenantDirectionsHaveIdenticalMissingIdResponsesAndNoListLeak() throws Exception {
        String a = account("docs-a");
        String b = account("docs-b");
        UUID aId = id(init(a, "tenant-a-key"));
        UUID bId = id(init(b, "tenant-b-key"));
        for (var pair : java.util.List.of(Map.entry(a, bId), Map.entry(b, aId))) {
            String path = "/api/documents/" + pair.getValue();
            var missing =
                    send("GET", "/api/documents/" + UUID.randomUUID(), pair.getKey(), null, null);
            var foreign = send("GET", path, pair.getKey(), null, null);
            assertThat(foreign.statusCode()).isEqualTo(404);
            assertThat(foreign.body()).isEqualTo(missing.body());
            assertThat(send("POST", path + "/complete", pair.getKey(), null, Map.of()).statusCode())
                    .isEqualTo(404);
            assertThat(send("DELETE", path, pair.getKey(), null, null).statusCode()).isEqualTo(404);
            assertThat(send("GET", "/api/documents", pair.getKey(), null, null).body())
                    .doesNotContain(pair.getValue().toString());
        }
        assertThat(send("GET", "/api/documents", null, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void softDeleteRetainsQuotaRestoresAsUploadedAndCannotResurrectCancelledIntent()
            throws Exception {
        String token = account("docs-a");
        UUID id = id(init(token, "retained-key"));
        String path = "/api/documents/" + id;
        send("POST", path + "/complete", token, null, Map.of());
        assertThat(send("DELETE", path, token, null, null).statusCode()).isEqualTo(204);
        assertThat(send("GET", path, token, null, null).statusCode()).isEqualTo(404);
        assertThat(
                        jdbc.sql("SELECT reserved_files FROM ingestion.upload_quotas")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(send("POST", path + "/restore", token, null, Map.of()).body())
                .contains("UPLOADED");
        UUID cancelled = id(init(token, "cancelled-key"));
        String cancelPath = "/api/documents/" + cancelled;
        send("DELETE", cancelPath, token, null, null);
        assertThat(send("POST", cancelPath + "/complete", token, null, Map.of()).statusCode())
                .isEqualTo(409);
        assertThat(send("POST", cancelPath + "/restore", token, null, Map.of()).statusCode())
                .isEqualTo(409);
    }

    private String account(String slug) throws Exception {
        assertThat(
                        send(
                                        "POST",
                                        "/api/auth/register",
                                        null,
                                        null,
                                        Map.of(
                                                "tenantSlug",
                                                slug,
                                                "email",
                                                "admin@example.test",
                                                "password",
                                                PASSWORD))
                                .statusCode())
                .isEqualTo(201);
        var login =
                send(
                        "POST",
                        "/api/auth/login",
                        null,
                        null,
                        Map.of(
                                "tenantSlug",
                                slug,
                                "email",
                                "admin@example.test",
                                "password",
                                PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        return json.readTree(login.body()).get("accessToken").asText();
    }

    private HttpResponse<String> init(String token, String key) throws Exception {
        return send(
                "POST",
                "/api/documents/uploads",
                token,
                key,
                Map.of("filename", "file.txt", "sizeBytes", 5));
    }

    private UUID id(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return UUID.fromString(json.readTree(response.body()).get("document").get("id").asText());
    }

    private HttpResponse<String> send(
            String method, String path, String token, String key, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
