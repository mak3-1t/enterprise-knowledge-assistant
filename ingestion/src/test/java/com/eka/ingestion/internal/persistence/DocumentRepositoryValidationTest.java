package com.eka.ingestion.internal.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DocumentRepositoryValidationTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final DocumentRepository documents = new DocumentRepository(jdbc);
    private final UUID tenant = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    @Test
    void invalidIdentityAndFilenamesAreRejectedBeforeSql() {
        assertThatThrownBy(
                        () -> documents.createUpload(null, id, id, "safe.pdf", DocumentFormat.PDF))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> documents.findUpload(tenant, null))
                .isInstanceOf(NullPointerException.class);
        for (String name :
                List.of(
                        "",
                        " ",
                        "../file.pdf",
                        "C:\\file.pdf",
                        "a\r\nb.pdf",
                        ".",
                        "..",
                        " padded.pdf ",
                        "a".repeat(256))) {
            assertThatThrownBy(
                            () -> documents.createUpload(tenant, id, id, name, DocumentFormat.PDF))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidCompletionIsRejectedBeforeSql() {
        for (long size : List.of(-1L, 0L, DocumentRepository.MAX_FILE_BYTES + 1)) {
            assertThatThrownBy(() -> documents.completeUpload(tenant, id, 0, size, "a".repeat(64)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> documents.completeUpload(tenant, id, 0, 10, "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> documents.completeUpload(tenant, id, -1, 10, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> documents.failUpload(tenant, id, -1))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
