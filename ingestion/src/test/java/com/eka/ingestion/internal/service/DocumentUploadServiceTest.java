package com.eka.ingestion.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.internal.persistence.DocumentFormat;
import com.eka.ingestion.internal.persistence.UploadRecord;
import com.eka.ingestion.internal.persistence.UploadRepository;
import com.eka.ingestion.internal.persistence.UploadStatus;
import com.eka.ingestion.internal.storage.UploadStorage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentUploadServiceTest {
    private final CurrentAccount current = mock(CurrentAccount.class);
    private final UploadRepository uploads = mock(UploadRepository.class);
    private final UploadStorage storage = mock(UploadStorage.class);
    private final DocumentUploadService service =
            new DocumentUploadService(current, uploads, storage, mock(UploadEvents.class));
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void viewerCannotCreateCompleteDeleteOrRestore() {
        identity("VIEWER");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.initiate("test-key", "file.txt", 1, null))
                .hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> service.complete(id)).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> service.delete(id)).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> service.restore(id)).hasMessage("FORBIDDEN");
        verify(uploads, never()).delete(any(), any(), anyBoolean(), any());
    }

    @Test
    void rejectsLegacyDocAndBadPagesWithoutAccessingStorage() {
        identity("EDITOR");
        assertThatThrownBy(() -> service.initiate("test-key", "file.doc", 10, null))
                .hasMessage("UNSUPPORTED_TYPE");
        assertThatThrownBy(() -> service.initiate("test-key", "file.docm", 10, null))
                .hasMessage("UNSUPPORTED_TYPE");
        assertThatThrownBy(() -> service.list(Integer.MAX_VALUE, 100)).hasMessage("INVALID_PAGE");
        assertThatThrownBy(() -> service.list(0, 0)).hasMessage("INVALID_PAGE");
        verify(storage, never()).authorize(any());
    }

    @Test
    void callerCannotChooseTenantAndOnlyReadyDocumentsCanDownload() {
        identity("VIEWER");
        var row = row(UploadStatus.UPLOADED);
        when(uploads.findVisible(tenant, row.id(), "VIEWER")).thenReturn(Optional.of(row));
        assertThat(service.detail(row.id()).id()).isEqualTo(row.id());
        assertThatThrownBy(() -> service.download(row.id())).hasMessage("DOCUMENT_NOT_READY");
        verify(storage, never()).open(any());
        when(uploads.findVisible(tenant, row.id(), "VIEWER")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(row.id())).hasMessage("NOT_FOUND");
        when(uploads.findVisible(tenant, row.id(), "VIEWER")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(row.id())).hasMessage("NOT_FOUND");
    }

    @Test
    void onlyAdminCanSetAValidatedReaderRoleSubset() {
        UUID id = UUID.randomUUID();
        identity("EDITOR");
        assertThatThrownBy(() -> service.setReadableRoles(id, List.of("VIEWER")))
                .hasMessage("FORBIDDEN");
        identity("ADMIN");
        when(uploads.setReaderRoles(tenant, id, List.of("VIEWER"))).thenReturn(List.of("VIEWER"));
        assertThat(service.setReadableRoles(id, List.of("VIEWER")))
                .isEqualTo(new DocumentUploadService.AccessView(id, List.of("VIEWER")));
        assertThatThrownBy(() -> service.setReadableRoles(id, List.of("ADMIN")))
                .hasMessage("INVALID_ACCESS_ROLES");
        assertThatThrownBy(() -> service.setReadableRoles(id, List.of("VIEWER", "VIEWER")))
                .hasMessage("INVALID_ACCESS_ROLES");
        assertThatThrownBy(
                        () -> service.setReadableRoles(id, java.util.Arrays.asList((String) null)))
                .hasMessage("INVALID_ACCESS_ROLES");
        verify(uploads).setReaderRoles(tenant, id, List.of("VIEWER"));
    }

    @Test
    void editorAndAdminPassDifferentDeletionAuthorityUsingVerifiedIdentity() {
        UUID id = UUID.randomUUID();
        identity("EDITOR");
        service.delete(id);
        verify(uploads).delete(eq(tenant), eq(user), eq(false), eq(id));
        identity("ADMIN");
        service.delete(id);
        verify(uploads).delete(eq(tenant), eq(user), eq(true), eq(id));
    }

    private void identity(String role) {
        when(current.require()).thenReturn(new AuthenticatedAccount(user, tenant, role));
    }

    private UploadRecord row(UploadStatus status) {
        return new UploadRecord(
                UUID.randomUUID(),
                tenant,
                user,
                "file.txt",
                DocumentFormat.TXT,
                "private",
                10,
                null,
                "a".repeat(64),
                status,
                1,
                Instant.now().plusSeconds(3600),
                status == UploadStatus.DELETED ? Instant.now() : null,
                null,
                10L,
                "a".repeat(64),
                null);
    }
}
