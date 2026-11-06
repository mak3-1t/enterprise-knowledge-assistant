package com.eka.ingestion.internal.storage;

import com.eka.ingestion.internal.persistence.UploadConflict;
import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.VerifiedUpload;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

/** POST is size-bound. Only the server can write the immutable original prefix. */
@Component
@ConditionalOnProperty(name = "eka.upload.enabled", havingValue = "true")
public class S3UploadStorage implements UploadStorage {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final S3Client s3;
    private final String bucket;
    private final URI publicEndpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final Clock clock = Clock.systemUTC();

    public S3UploadStorage(
            S3Client s3,
            @Value("${eka.upload.bucket}") String bucket,
            @Value("${eka.upload.public-endpoint:${eka.upload.endpoint}}") URI publicEndpoint,
            @Value("${eka.upload.region:us-east-1}") String region,
            @Value("${eka.upload.access-key}") String accessKey,
            @Value("${eka.upload.secret-key}") String secretKey) {
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || publicEndpoint.getHost() == null
                || publicEndpoint.getQuery() != null
                || publicEndpoint.getFragment() != null
                || publicEndpoint.getUserInfo() != null
                || !("https".equals(publicEndpoint.getScheme())
                        || ("http".equals(publicEndpoint.getScheme())
                                && List.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(publicEndpoint.getHost())))) {
            throw new IllegalArgumentException("Invalid upload storage configuration");
        }
        this.s3 = s3;
        this.bucket = bucket;
        this.publicEndpoint = publicEndpoint;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public UploadForm authorize(UploadRecord row) {
        Instant now = clock.instant();
        Instant expiry = now.plus(Duration.ofMinutes(10));
        if (expiry.isAfter(row.expiresAt())) {
            expiry = row.expiresAt();
        }
        if (!expiry.isAfter(now)) {
            throw new UploadConflict("UPLOAD_EXPIRED");
        }
        String date = DATE.format(now);
        String scope = date + "/" + region + "/s3/aws4_request";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", row.stagingKey());
        fields.put("Content-Type", row.format().mediaType());
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", accessKey + "/" + scope);
        fields.put("x-amz-date", TIME.format(now));
        var conditions = new java.util.ArrayList<Object>();
        conditions.add(Map.of("bucket", bucket));
        fields.forEach((key, value) -> conditions.add(Map.of(key, value)));
        conditions.add(List.of("content-length-range", row.declaredSize(), row.declaredSize()));
        String policy =
                Base64.getEncoder()
                        .encodeToString(
                                JsonMapper.builder()
                                        .build()
                                        .writeValueAsBytes(
                                                Map.of(
                                                        "expiration",
                                                        expiry.toString(),
                                                        "conditions",
                                                        conditions)));
        byte[] signingKey =
                hmac(
                        hmac(
                                hmac(
                                        hmac(
                                                ("AWS4" + secretKey)
                                                        .getBytes(StandardCharsets.UTF_8),
                                                date),
                                        region),
                                "s3"),
                        "aws4_request");
        fields.put("policy", policy);
        fields.put("x-amz-signature", HexFormat.of().formatHex(hmac(signingKey, policy)));
        String base = publicEndpoint.toString().replaceAll("/+$", "");
        return new UploadForm(URI.create(base + "/" + bucket), Map.copyOf(fields), expiry);
    }

    @Override
    public VerifiedUpload seal(UploadRecord row) {
        Path temporary = null;
        try {
            try (var existing = get(row.objectKey())) {
                return measure(existing, OutputStream.nullOutputStream(), row);
            } catch (S3Exception missing) {
                if (missing.statusCode() != 404) {
                    throw missing;
                }
            }
            temporary = Files.createTempFile("eka-upload-", ".quarantine");
            VerifiedUpload measured;
            try (var input = get(row.stagingKey());
                    var output = Files.newOutputStream(temporary)) {
                measured = measure(input, output, row);
            }
            try {
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(row.objectKey())
                                .contentType(row.format().mediaType())
                                .contentLength(measured.bytes())
                                .ifNoneMatch("*")
                                .build(),
                        RequestBody.fromFile(temporary));
            } catch (S3Exception raced) {
                if (raced.statusCode() != 412 && raced.statusCode() != 409) {
                    throw raced;
                }
                try (var existing = get(row.objectKey())) {
                    return measure(existing, OutputStream.nullOutputStream(), row);
                }
            }
            return measured;
        } catch (IOException | SdkException unavailable) {
            throw new StorageUnavailable();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    // Report only operational context; never the path or document contents.
                    System.getLogger(getClass().getName())
                            .log(
                                    System.Logger.Level.WARNING,
                                    "Upload temporary file cleanup failed");
                }
            }
        }
    }

    @Override
    public InputStream open(UploadRecord row) {
        try {
            return get(row.objectKey());
        } catch (SdkException unavailable) {
            throw new StorageUnavailable();
        }
    }

    @Override
    public void remove(UploadRecord row) {
        try {
            s3.deleteObject(r -> r.bucket(bucket).key(row.stagingKey()));
            s3.deleteObject(r -> r.bucket(bucket).key(row.objectKey()));
        } catch (SdkException unavailable) {
            throw new StorageUnavailable();
        }
    }

    private InputStream get(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private static VerifiedUpload measure(InputStream input, OutputStream output, UploadRecord row)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        long bytes = 0;
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes += read;
            if (bytes > row.declaredSize()) {
                throw new UploadConflict("FILE_TOO_LARGE");
            }
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        if (bytes != row.declaredSize()
                || (row.requestedSha256() != null && !row.requestedSha256().equals(hash))) {
            throw new UploadConflict("CHECKSUM_OR_SIZE_MISMATCH");
        }
        return new VerifiedUpload(bytes, hash);
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
