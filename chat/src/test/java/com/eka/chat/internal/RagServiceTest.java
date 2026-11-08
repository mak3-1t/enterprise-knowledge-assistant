package com.eka.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RagServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void sendsQuestionAndBoundedEvidenceInSeparatePromptRoles() {
        var service =
                new RagService(
                        (question, limit) -> {
                            assertThat(question).isEqualTo("Chính sách là gì?");
                            assertThat(limit).isEqualTo(5);
                            return List.of(match("Hai ngày mỗi tuần."), match("x".repeat(12000)));
                        },
                        (instructions, input) -> {
                            assertThat(instructions).contains("untrusted data");
                            var payload = JSON.readTree(input);
                            assertThat(payload.get("question").asText())
                                    .isEqualTo("Chính sách là gì?");
                            assertThat(payload.get("evidence").size()).isEqualTo(1);
                            assertThat(payload.get("evidence").get(0).asText())
                                    .isEqualTo("Hai ngày mỗi tuần.");
                            return "Hai ngày mỗi tuần.";
                        },
                        JSON);
        assertThat(service.answer(" Chính sách là gì? "))
                .isEqualTo(new RagService.Answer("ANSWERED", "Hai ngày mỗi tuần."));
    }

    @Test
    void skipsModelWithoutEvidenceAndRejectsInvalidInput() {
        var service =
                new RagService(
                        (question, limit) -> List.of(),
                        (instructions, input) -> {
                            throw new AssertionError("Model should not be called");
                        },
                        JSON);
        assertThat(service.answer("unknown")).isEqualTo(new RagService.Answer("NO_EVIDENCE", null));
        assertThatThrownBy(() -> service.answer(" ")).hasMessage("INVALID_QUESTION");
        assertThatThrownBy(() -> service.answer("x".repeat(2001))).hasMessage("INVALID_QUESTION");
    }

    @Test
    void sanitizesProviderErrors() {
        var service =
                new RagService(
                        (question, limit) -> List.of(match("source")),
                        (instructions, input) -> {
                            throw new IllegalStateException("private provider detail");
                        },
                        JSON);
        assertThatThrownBy(() -> service.answer("question"))
                .isInstanceOf(ChatUnavailable.class)
                .hasMessage("CHAT_UNAVAILABLE")
                .hasNoCause();
    }

    private static Match match(String text) {
        return new Match(UUID.randomUUID(), "test.txt", 1, 0, 0, null, 0, text.length(), text, 0.0);
    }
}
