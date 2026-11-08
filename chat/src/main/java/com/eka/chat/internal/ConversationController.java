package com.eka.chat.internal;

import com.eka.auth.api.CurrentAccount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/conversations")
@ConditionalOnProperty(name = "eka.chat.enabled", havingValue = "true")
class ConversationController {
    private final ConversationRepository repository;
    private final CurrentAccount current;
    private final RagService rag;

    ConversationController(
            ConversationRepository repository, CurrentAccount current, RagService rag) {
        this.repository = repository;
        this.current = current;
        this.rag = rag;
    }

    @PostMapping
    public ResponseEntity<ConversationRepository.Conversation> create() {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(repository.create(current.require()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<History> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        var account = current.require();
        var conversation = repository.require(account, id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new History(
                                conversation.revision(),
                                repository.history(
                                        account,
                                        id,
                                        Math.min(before, conversation.revision() + 1))));
    }

    @PostMapping("/{id}/turns")
    public ResponseEntity<Completed> ask(@PathVariable UUID id, @RequestBody Question request) {
        if (request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new IllegalArgumentException("INVALID_REVISION");
        }
        var account = current.require();
        long expected = request.expectedRevision();
        if (repository.require(account, id).revision() != expected) {
            throw new ConversationConflict("CONVERSATION_CHANGED");
        }
        var history = boundedHistory(repository.recentQuestions(account, id, expected));
        var answer = rag.answer(request.question(), history);
        repository.append(account, id, expected, request.question().strip(), answer);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new Completed(expected + 1, answer));
    }

    private static List<String> boundedHistory(List<String> newestFirst) {
        var included = new ArrayList<String>();
        int remaining = 4000;
        for (String question : newestFirst) {
            if (question.length() > remaining) {
                break;
            }
            included.add(question);
            remaining -= question.length();
        }
        Collections.reverse(included);
        return List.copyOf(included);
    }

    @ExceptionHandler(ConversationConflict.class)
    ResponseEntity<Map<String, String>> conflict(ConversationConflict error) {
        String code = error.getMessage();
        return errorResponse(code.equals("CONVERSATION_NOT_FOUND") ? 404 : 409, code);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return errorResponse(
                400,
                "INVALID_REVISION".equals(error.getMessage())
                        ? "INVALID_REVISION"
                        : "INVALID_QUESTION");
    }

    @ExceptionHandler(ChatUnavailable.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return errorResponse(503, "CHAT_UNAVAILABLE");
    }

    private static ResponseEntity<Map<String, String>> errorResponse(int status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", code));
    }

    record Question(String question, Long expectedRevision) {}

    record History(long revision, List<ConversationRepository.Turn> turns) {}

    record Completed(long revision, RagService.Answer result) {}
}
