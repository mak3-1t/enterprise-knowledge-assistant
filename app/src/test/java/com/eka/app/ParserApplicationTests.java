package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.eka.ingestion.internal.parsing.ParseJobRepository;
import com.eka.ingestion.internal.scan.MalwareScanner;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sprint 2 acceptance through the application and its scheduled workers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ParserApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    @Container
    static final GenericContainer<?> S3 =
            new GenericContainer<>("pgsty/minio:latest")
                    .withEnv("MINIO_ROOT_USER", "acceptance-access")
                    .withEnv("MINIO_ROOT_PASSWORD", "acceptance-secret-only")
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static final String BUCKET = "sprint-two-acceptance";
    private static final String TOPIC = "parser-app-" + UUID.randomUUID();
    private static final String MODEL = "fixture";
    private static final String PASSWORD = "Document-test-password";
    private static final CountDownLatch MODEL_REQUEST = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_MODEL = new CountDownLatch(1);
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicInteger CHAT_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> CHAT_BODY = new AtomicReference<>();
    private static HttpServer modelServer;

    @Value("${local.server.port}")
    int port;

    @Autowired ParseJobRepository parses;
    @Autowired JdbcClient jdbc;
    @Autowired S3Client objects;
    @Autowired ObjectMapper json;
    @MockitoBean MalwareScanner malware;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        startModel();
        TestSigningKeys.configure(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("eka.auth.registration.enabled", () -> "true");
        registry.add("eka.ingestion.kafka.enabled", () -> "true");
        registry.add("eka.ingestion.kafka.create-topic", () -> "true");
        registry.add("eka.ingestion.kafka.topic", () -> TOPIC);
        registry.add("eka.ingestion.parser.enabled", () -> "true");
        registry.add("eka.ingestion.parser.group-id", () -> "app-test-" + TOPIC);
        registry.add("eka.ingestion.embedding.enabled", () -> "true");
        registry.add("eka.ingestion.embedding.model", () -> MODEL);
        registry.add("eka.ingestion.embedding.model-version", () -> "acceptance-v1");
        registry.add(
                "eka.ingestion.embedding.base-url",
                () -> "http://127.0.0.1:" + modelServer.getAddress().getPort());
        registry.add("eka.chat.enabled", () -> "true");
        registry.add("eka.chat.model", () -> MODEL);
        registry.add(
                "eka.chat.base-url",
                () -> "http://127.0.0.1:" + modelServer.getAddress().getPort());
        registry.add("eka.upload.enabled", () -> "true");
        registry.add("eka.upload.scan.enabled", () -> "true");
        registry.add("eka.upload.endpoint", ParserApplicationTests::objectEndpoint);
        registry.add("eka.upload.public-endpoint", ParserApplicationTests::objectEndpoint);
        registry.add("eka.upload.bucket", () -> BUCKET);
        registry.add("eka.upload.access-key", () -> "acceptance-access");
        registry.add("eka.upload.secret-key", () -> "acceptance-secret-only");
    }

    @AfterAll
    static void stopModel() {
        RELEASE_MODEL.countDown();
        if (modelServer != null) {
            modelServer.stop(0);
        }
    }

    @Test
    void uploadThroughReadyAndDownloadRemainTenantScoped() throws Exception {
        objects.createBucket(request -> request.bucket(BUCKET));
        String owner = account("sprint-two-a");
        String foreign = account("sprint-two-b");
        var intent =
                request(
                        "POST",
                        "/api/documents/uploads",
                        owner,
                        "acceptance-key",
                        Map.of("filename", "hello.txt", "sizeBytes", 5));
        assertThat(intent.statusCode()).isEqualTo(200);
        var created = json.readTree(intent.body());
        String id = created.get("document").get("id").asText();
        String path = "/api/documents/" + id;
        assertThat(created.get("document").get("status").asText()).isEqualTo("PENDING");
        postOriginal(created.get("upload"));
        var complete = request("POST", path + "/complete", owner, null, Map.of());
        assertThat(complete.statusCode()).isEqualTo(200);
        assertThat(json.readTree(complete.body()).get("status").asText()).isEqualTo("UPLOADED");

        try {
            assertThat(MODEL_REQUEST.await(60, TimeUnit.SECONDS)).isTrue();
            var modelInput = json.readTree(REQUEST_BODY.get());
            assertThat(modelInput.get("input").get(0).asText()).isEqualTo("hello");
            assertThat(modelInput.get("truncate").asBoolean()).isFalse();
            var processing = request("GET", path, owner, null, null);
            assertThat(processing.statusCode()).isEqualTo(200);
            assertThat(json.readTree(processing.body()).get("status").asText())
                    .isEqualTo("PROCESSING");
            assertThat(request("GET", path + "/download", owner, null, null).statusCode())
                    .isEqualTo(409);
            assertThat(vectorCount(UUID.fromString(id))).isZero();
        } finally {
            RELEASE_MODEL.countDown();
        }

        var ready = awaitReady(path, owner);
        assertThat(ready.get("version").asLong())
                .isGreaterThan(json.readTree(complete.body()).get("version").asLong());
        assertThat(vectorCount(UUID.fromString(id))).isEqualTo(1);
        verify(malware).scan(any());
        var download = request("GET", path + "/download", owner, null, null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo("hello");

        var missing = request("GET", "/api/documents/" + UUID.randomUUID(), foreign, null, null);
        var denied = request("GET", path, foreign, null, null);
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(denied.body()).isEqualTo(missing.body());
        assertThat(request("GET", path + "/download", foreign, null, null).statusCode())
                .isEqualTo(404);
        var question = Map.of("question", "What text is in the document?");
        var answer = request("POST", "/api/chat", owner, null, question);
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(json.readTree(answer.body()).get("answer").asText())
                .isEqualTo("The text is hello.");
        var messages = json.readTree(CHAT_BODY.get()).get("messages");
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        var input = json.readTree(messages.get(1).get("content").asText());
        assertThat(input.get("evidence").get(0).asText()).isEqualTo("hello");

        var noEvidence = request("POST", "/api/chat", foreign, null, question);
        assertThat(noEvidence.statusCode()).isEqualTo(200);
        assertThat(json.readTree(noEvidence.body()).get("status").asText())
                .isEqualTo("NO_EVIDENCE");
        assertThat(CHAT_REQUESTS.get()).isEqualTo(1);
        assertThat(request("POST", "/api/chat", null, null, question).statusCode()).isEqualTo(401);
        var stream = request("POST", "/api/chat/stream", owner, null, question);
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("text/event-stream"));
        assertThat(stream.body())
                .containsSubsequence(
                        "event:delta",
                        "The text is ",
                        "event:delta",
                        "hello.",
                        "event:done",
                        "ANSWERED");
        var emptyStream = request("POST", "/api/chat/stream", foreign, null, question);
        assertThat(emptyStream.statusCode()).isEqualTo(200);
        assertThat(emptyStream.body())
                .contains("event:done", "NO_EVIDENCE")
                .doesNotContain("event:delta");
        assertThat(CHAT_REQUESTS.get()).isEqualTo(2);
        assertThat(parses.loadCurrent(UUID.randomUUID(), UUID.fromString(id))).isEmpty();

        assertThat(request("DELETE", path, owner, null, null).statusCode()).isEqualTo(204);
        assertThat(vectorCount(UUID.fromString(id))).isZero();
        assertThat(request("GET", path + "/download", owner, null, null).statusCode())
                .isEqualTo(404);
    }

    private static String objectEndpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9000);
    }

    private static void startModel() throws IOException {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext(
                "/api/embed",
                exchange -> {
                    try (exchange) {
                        REQUEST_BODY.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        MODEL_REQUEST.countDown();
                        if (!RELEASE_MODEL.await(30, TimeUnit.SECONDS)) {
                            exchange.sendResponseHeaders(503, -1);
                            return;
                        }
                        byte[] payload =
                                ("{\"model\":\"fixture\",\"embeddings\":[[1"
                                                + ",0".repeat(1023)
                                                + "]]}")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, payload.length);
                        exchange.getResponseBody().write(payload);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        modelServer.createContext(
                "/api/chat",
                exchange -> {
                    try (exchange) {
                        CHAT_REQUESTS.incrementAndGet();
                        CHAT_BODY.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        if (CHAT_BODY.get().contains("\"stream\":true")) {
                            byte[] stream =
                                    """
                                    {"model":"fixture","message":{"role":"assistant","content":"The text is "},"done":false}
                                    {"model":"fixture","message":{"role":"assistant","content":"hello."},"done":false}
                                    {"model":"fixture","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5,"total_duration":10,"load_duration":1,"prompt_eval_duration":1,"eval_duration":1}
                                    """
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders()
                                    .set("Content-Type", "application/x-ndjson");
                            exchange.sendResponseHeaders(200, stream.length);
                            exchange.getResponseBody().write(stream);
                            return;
                        }
                        byte[] payload =
                                """
                        {"model":"fixture","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"The text is hello."},"done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5,"total_duration":10,"load_duration":1,"prompt_eval_duration":1,"eval_duration":1}
                        """
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, payload.length);
                        exchange.getResponseBody().write(payload);
                    }
                });
        modelServer.start();
    }

    private static void postOriginal(JsonNode form) throws Exception {
        String boundary = "eka-" + UUID.randomUUID();
        var body = new StringBuilder();
        form.get("fields")
                .properties()
                .forEach(
                        field ->
                                body.append("--")
                                        .append(boundary)
                                        .append("\r\nContent-Disposition: form-data; name=\"")
                                        .append(field.getKey())
                                        .append("\"\r\n\r\n")
                                        .append(field.getValue().asText())
                                        .append("\r\n"));
        body.append("--")
                .append(boundary)
                .append(
                        "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n")
                .append("Content-Type: text/plain\r\n\r\nhello\r\n--")
                .append(boundary)
                .append("--\r\n");
        var request =
                HttpRequest.newBuilder(URI.create(form.get("url").asText()))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isBetween(200, 299);
    }

    private JsonNode awaitReady(String path, String token) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do {
            var response = request("GET", path, token, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            var state = json.readTree(response.body());
            if (state.get("status").asText().equals("READY")) {
                return state;
            }
            assertThat(state.get("status").asText()).isEqualTo("PROCESSING");
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Document did not reach READY before the deadline");
    }

    private long vectorCount(UUID document) {
        return jdbc.sql(
                        "SELECT count(*) FROM ingestion.document_chunk_embeddings WHERE document_id = ?")
                .param(document)
                .query(Long.class)
                .single();
    }

    private String account(String slug) throws Exception {
        var credentials =
                Map.of("tenantSlug", slug, "email", "admin@example.test", "password", PASSWORD);
        assertThat(request("POST", "/api/auth/register", null, null, credentials).statusCode())
                .isEqualTo(201);
        var login = request("POST", "/api/auth/login", null, null, credentials);
        assertThat(login.statusCode()).isEqualTo(200);
        return json.readTree(login.body()).get("accessToken").asText();
    }

    private HttpResponse<String> request(
            String method, String path, String token, String key, Object body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
