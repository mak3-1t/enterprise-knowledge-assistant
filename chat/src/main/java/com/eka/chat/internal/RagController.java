package com.eka.chat.internal;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "eka.chat.enabled", havingValue = "true")
class RagController {
    private final RagService service;

    RagController(RagService service) {
        this.service = service;
    }

    @PostMapping("/api/chat")
    public ResponseEntity<RagService.Answer> chat(@RequestBody Question request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.answer(request.question()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidQuestion() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "INVALID_QUESTION"));
    }

    @ExceptionHandler(ChatUnavailable.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "CHAT_UNAVAILABLE"));
    }

    record Question(String question) {}
}
