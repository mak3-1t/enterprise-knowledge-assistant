package com.eka.agent.internal;

import com.eka.agent.api.AgentService;
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
class AgentController {
    private final AgentService agent;

    AgentController(AgentService agent) {
        this.agent = agent;
    }

    @PostMapping("/api/agent")
    ResponseEntity<AgentService.Answer> answer(@RequestBody Question request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(agent.answer(request.question()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidQuestion() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "INVALID_QUESTION"));
    }

    @ExceptionHandler(AgentUnavailable.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "AGENT_UNAVAILABLE"));
    }

    record Question(String question) {}
}
