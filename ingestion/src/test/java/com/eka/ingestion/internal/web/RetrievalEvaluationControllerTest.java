package com.eka.ingestion.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.DocumentRetrieval.Match;
import com.eka.ingestion.api.HybridDocumentSearch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RetrievalEvaluationControllerTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final HybridDocumentSearch search = mock(HybridDocumentSearch.class);

    @Test
    void returnsBoundedRankedCandidatesForTenantAdmin() {
        var account = account("ADMIN");
        var current = mock(CurrentAccount.class);
        when(current.require()).thenReturn(account);
        var match =
                new Match(
                        UUID.randomUUID(),
                        "a01.md",
                        1,
                        0,
                        0,
                        null,
                        0,
                        120,
                        "Synthetic policy text.",
                        0.1,
                        true);
        when(search.search("remote work", 10)).thenReturn(List.of(match));

        var response =
                new RetrievalEvaluationController(current, search)
                        .search(new RetrievalEvaluationController.Request("remote work", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody())
                .isEqualTo(
                        new RetrievalEvaluationController.Result(
                                List.of(
                                        new RetrievalEvaluationController.Candidate(
                                                match.documentId(),
                                                match.filename(),
                                                match.sourceGeneration(),
                                                match.chunkOrdinal(),
                                                match.text()))));
        verify(search).search("remote work", 10);
    }

    @Test
    void rejectsNonAdminWithoutSearching() {
        var current = mock(CurrentAccount.class);
        when(current.require()).thenReturn(account("EDITOR"));

        var response =
                new RetrievalEvaluationController(current, search)
                        .search(new RetrievalEvaluationController.Request("policy", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(search, never()).search("policy", 5);
    }

    @Test
    void rejectsInvalidLimitBeforeAccountLookup() {
        var current = mock(CurrentAccount.class);

        var response =
                new RetrievalEvaluationController(current, search)
                        .search(new RetrievalEvaluationController.Request("policy", 11));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(current, never()).require();
        verify(search, never()).search("policy", 11);
    }

    private AuthenticatedAccount account(String role) {
        return new AuthenticatedAccount(userId, tenantId, role);
    }
}
