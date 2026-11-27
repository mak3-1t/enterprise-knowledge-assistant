package com.eka.ingestion.internal.web;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.DocumentRetrieval.Match;
import com.eka.ingestion.api.HybridDocumentSearch;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local evaluation-only export of the authenticated account's ranked retrieval candidates. */
@RestController
@RequestMapping("/api/evaluation/retrieval")
@Profile("evaluation")
@ConditionalOnProperty(name = "eka.evaluation.retrieval.enabled", havingValue = "true")
class RetrievalEvaluationController {
    private final CurrentAccount current;
    private final HybridDocumentSearch search;

    RetrievalEvaluationController(CurrentAccount current, HybridDocumentSearch search) {
        this.current = current;
        this.search = search;
    }

    @PostMapping
    ResponseEntity<?> search(@RequestBody Request request) {
        if (request == null
                || request.question() == null
                || request.question().isBlank()
                || request.question().length() > 2000
                || (request.limit() != null && (request.limit() < 1 || request.limit() > 10))) {
            return response(400, Map.of("error", "INVALID_EVALUATION_REQUEST"));
        }
        AuthenticatedAccount account = current.require();
        if (!"ADMIN".equals(account.role())) {
            return response(403, Map.of("error", "FORBIDDEN"));
        }
        int limit = request.limit() == null ? 10 : request.limit();
        List<Candidate> candidates =
                search.search(request.question().strip(), limit).stream()
                        .map(RetrievalEvaluationController::candidate)
                        .toList();
        return response(200, new Result(candidates));
    }

    private static Candidate candidate(Match match) {
        return new Candidate(
                match.documentId(),
                match.filename(),
                match.sourceGeneration(),
                match.chunkOrdinal(),
                match.text());
    }

    private static ResponseEntity<?> response(int status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    record Request(String question, Integer limit) {}

    record Candidate(
            UUID documentId,
            String filename,
            long sourceGeneration,
            int chunkOrdinal,
            String text) {}

    record Result(List<Candidate> candidates) {}
}
