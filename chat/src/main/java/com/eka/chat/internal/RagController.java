package com.eka.chat.internal;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Map<String, Object>>>> stream(
            @RequestBody Question request) {
        var events =
                service.stream(request.question())
                        .map(
                                event ->
                                        ServerSentEvent.builder(Map.of("value", event.value()))
                                                .event(event.name())
                                                .build());
        var withHeartbeats =
                events.publish(
                        shared ->
                                Flux.merge(
                                        shared,
                                        Flux.interval(Duration.ofSeconds(5))
                                                .map(
                                                        ignored ->
                                                                ServerSentEvent
                                                                        .<Map<String, Object>>
                                                                                builder()
                                                                        .comment("keepalive")
                                                                        .build())
                                                .takeUntilOther(shared.ignoreElements())));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(withHeartbeats);
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
