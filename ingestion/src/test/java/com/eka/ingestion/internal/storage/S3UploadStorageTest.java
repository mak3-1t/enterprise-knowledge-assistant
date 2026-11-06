package com.eka.ingestion.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadStatus;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3UploadStorageTest {
    private final S3Client s3 = mock(S3Client.class);
    private final S3UploadStorage storage =
            new S3UploadStorage(
                    s3,
                    "test-bucket",
                    URI.create("http://localhost:9000"),
                    "us-east-1",
                    "test-access",
                    "test-secret");

    @Test
    void postPolicyBindsBucketKeyExactSizeTypeAndExpiryWithoutLeakingSigningSecret() {
        var row = row(12, null);
        var form = storage.authorize(row);
        String policy =
                new String(
                        Base64.getDecoder().decode(form.fields().get("policy")),
                        StandardCharsets.UTF_8);
        assertThat(policy)
                .contains(
                        "content-length-range",
                        "12,12",
                        row.stagingKey(),
                        "test-bucket",
                        "text/plain");
        assertThat(form.fields().get("key")).isEqualTo(row.stagingKey());
        assertThat(form.fields().get("x-amz-signature")).matches("[0-9a-f]{64}");
        assertThat(form.url()).isEqualTo(URI.create("http://localhost:9000/test-bucket"));
        assertThat(form.expiresAt()).isBeforeOrEqualTo(row.expiresAt());
        assertThat(form.toString()).doesNotContain("test-access", "test-secret", "signature");
    }

    @Test
    void completeMeasuresActualBytesAndWritesOriginalConditionally() throws Exception {
        var row = row(5, null);
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        call -> {
                            GetObjectRequest request = call.getArgument(0);
                            if (request.key().equals(row.objectKey())) {
                                throw S3Exception.builder().statusCode(404).build();
                            }
                            return stream("hello");
                        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> {
                            PutObjectRequest request = call.getArgument(0);
                            RequestBody body = call.getArgument(1);
                            assertThat(request.key()).isEqualTo(row.objectKey());
                            assertThat(request.ifNoneMatch()).isEqualTo("*");
                            try (var input = body.contentStreamProvider().newStream()) {
                                assertThat(input.readAllBytes())
                                        .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
                            }
                            return PutObjectResponse.builder().build();
                        });
        var measured = storage.seal(row);
        assertThat(measured.bytes()).isEqualTo(5);
        assertThat(measured.sha256()).isEqualTo(hash("hello"));
    }

    @Test
    void existingImmutableOriginalWinsOverAnyLaterStagingOverwrite() throws Exception {
        var row = row(5, null);
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> stream("first"));
        assertThat(storage.seal(row).sha256()).isEqualTo(hash("first"));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void oversizedTruncatedAndIncorrectChecksumNeverWriteOriginal() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        call -> {
                            GetObjectRequest request = call.getArgument(0);
                            if (request.key().endsWith("/original")) {
                                throw S3Exception.builder().statusCode(404).build();
                            }
                            return stream("hello");
                        });
        assertThatThrownBy(() -> storage.seal(row(4, null))).hasMessage("FILE_TOO_LARGE");
        assertThatThrownBy(() -> storage.seal(row(6, null)))
                .hasMessage("CHECKSUM_OR_SIZE_MISMATCH");
        assertThatThrownBy(() -> storage.seal(row(5, "a".repeat(64))))
                .hasMessage("CHECKSUM_OR_SIZE_MISMATCH");
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void storageFailureDoesNotLeakEndpointCredentialsOrSdkMessage() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(
                        S3Exception.builder().statusCode(503).message("secret diagnostic").build());
        assertThatThrownBy(() -> storage.seal(row(5, null)))
                .isInstanceOf(StorageUnavailable.class)
                .hasMessage("STORAGE_UNAVAILABLE")
                .hasNoCause();
    }

    private static ResponseInputStream<GetObjectResponse> stream(String text) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static UploadRecord row(long bytes, String hash) {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        return new UploadRecord(
                id,
                tenant,
                UUID.randomUUID(),
                "test.txt",
                DocumentFormat.TXT,
                "tenants/" + tenant + "/documents/" + id + "/original",
                bytes,
                hash,
                "a".repeat(64),
                UploadStatus.PENDING,
                0,
                Instant.now().plusSeconds(3600),
                null,
                null,
                null,
                null,
                null);
    }
}
