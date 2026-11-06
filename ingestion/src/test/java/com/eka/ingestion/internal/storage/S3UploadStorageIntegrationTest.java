package com.eka.ingestion.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Real S3-compatible transport contract, including server enforcement of the signed POST policy.
 */
@Testcontainers
class S3UploadStorageIntegrationTest {
    // Matches the current development Compose service; pin both when its supply-chain ADR lands.
    @Container
    static final GenericContainer<?> S3 =
            new GenericContainer<>("pgsty/minio:latest")
                    .withEnv("MINIO_ROOT_USER", "integration-access")
                    .withEnv("MINIO_ROOT_PASSWORD", "integration-secret-only")
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @Test
    void policyEnforcesBytesAndKeyAndSealedOriginalSurvivesLatePut() throws Exception {
        URI endpoint = URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9000));
        try (var client =
                S3Client.builder()
                        .endpointOverride(endpoint)
                        .region(Region.US_EAST_1)
                        .forcePathStyle(true)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                "integration-access", "integration-secret-only")))
                        .httpClientBuilder(
                                UrlConnectionHttpClient.builder()
                                        .socketTimeout(Duration.ofSeconds(10)))
                        .build()) {
            String bucket = "upload-contract";
            client.createBucket(r -> r.bucket(bucket));
            var storage =
                    new S3UploadStorage(
                            client,
                            bucket,
                            endpoint,
                            "us-east-1",
                            "integration-access",
                            "integration-secret-only");
            UUID tenant = UUID.randomUUID();
            UUID id = UUID.randomUUID();
            var row =
                    new UploadRecord(
                            id,
                            tenant,
                            UUID.randomUUID(),
                            "file.txt",
                            DocumentFormat.TXT,
                            "tenants/" + tenant + "/documents/" + id + "/original",
                            5,
                            null,
                            "a".repeat(64),
                            UploadStatus.PENDING,
                            0,
                            Instant.now().plusSeconds(3600),
                            null,
                            null,
                            null,
                            null,
                            null);
            var form = storage.authorize(row);
            assertThat(post(form, form.fields(), "hello")).isBetween(200, 299);
            assertThat(post(form, form.fields(), "too-large")).isBetween(400, 499);
            var changed = new java.util.HashMap<>(form.fields());
            changed.put("key", "tenants/forged-original");
            assertThat(post(form, changed, "hello")).isBetween(400, 499);
            var original = storage.seal(row);
            client.putObject(
                    r -> r.bucket(bucket).key(row.stagingKey()), RequestBody.fromString("later"));
            assertThat(storage.seal(row)).isEqualTo(original);
            try (var input = storage.open(row)) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("hello");
            }
            storage.remove(row);
            assertThat(client.listObjectsV2(r -> r.bucket(bucket)).contents()).isEmpty();
        }
    }

    private static int post(UploadStorage.UploadForm form, Map<String, String> fields, String file)
            throws Exception {
        String boundary = "eka-boundary-" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        fields.forEach(
                (key, value) ->
                        body.append("--")
                                .append(boundary)
                                .append("\r\nContent-Disposition: form-data; name=\"")
                                .append(key)
                                .append("\"\r\n\r\n")
                                .append(value)
                                .append("\r\n"));
        body.append("--")
                .append(boundary)
                .append(
                        "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file.txt\"\r\n")
                .append("Content-Type: text/plain\r\n\r\n")
                .append(file)
                .append("\r\n--")
                .append(boundary)
                .append("--\r\n");
        var request =
                HttpRequest.newBuilder(form.url())
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
