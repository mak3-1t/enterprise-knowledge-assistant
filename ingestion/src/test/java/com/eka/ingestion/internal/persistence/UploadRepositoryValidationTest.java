package com.eka.ingestion.internal.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class UploadRepositoryValidationTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final UploadRepository uploads = new UploadRepository(jdbc);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void pathsControlsBidiAndAlternateDataStreamsNeverReachDatabase() {
        for (String name :
                List.of(
                        "../a.pdf",
                        "C:\\a.pdf",
                        "file:stream.txt",
                        "bad\r\n.txt",
                        "a\u202Efdp.txt",
                        " ",
                        " padded.txt",
                        "a".repeat(256))) {
            assertThatThrownBy(
                            () ->
                                    uploads.initiate(
                                            tenant,
                                            user,
                                            "request-key",
                                            name,
                                            DocumentFormat.PDF,
                                            1,
                                            null))
                    .hasMessage("INVALID_FILENAME");
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidSizesChecksumsAndIdempotencyKeysCannotReserveQuota() {
        for (long size : List.of(0L, -1L, 20971521L, Long.MAX_VALUE)) {
            assertThatThrownBy(
                            () ->
                                    uploads.initiate(
                                            tenant,
                                            user,
                                            "request-key",
                                            "file.txt",
                                            DocumentFormat.TXT,
                                            size,
                                            null))
                    .hasMessage("FILE_TOO_LARGE");
        }
        assertThatThrownBy(
                        () ->
                                uploads.initiate(
                                        tenant,
                                        user,
                                        "bad key",
                                        "file.txt",
                                        DocumentFormat.TXT,
                                        1,
                                        null))
                .hasMessage("INVALID_IDEMPOTENCY_KEY");
        assertThatThrownBy(
                        () ->
                                uploads.initiate(
                                        tenant,
                                        user,
                                        "request-key",
                                        "file.txt",
                                        DocumentFormat.TXT,
                                        1,
                                        "not-sha256"))
                .hasMessage("INVALID_CHECKSUM");
        verifyNoInteractions(jdbc);
    }
}
