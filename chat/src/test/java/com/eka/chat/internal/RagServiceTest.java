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
                            assertThat(payload.get("evidence").get(0).get("text").asText())
                                    .isEqualTo("Hai ngày mỗi tuần.");
                            return "Hai ngày mỗi tuần. [S1]";
                        },
                        JSON,
                        0.45);
        var answer = service.answer(" Chính sách là gì? ");
        assertThat(answer.status()).isEqualTo("ANSWERED");
        assertThat(answer.answer()).isEqualTo("Hai ngày mỗi tuần. [S1]");
        assertThat(answer.citations())
                .extracting(RagService.Citation::filename)
                .containsExactly("test.txt");
    }

    @Test
    void skipsModelWithoutEvidenceAndRejectsInvalidInput() {
        var service =
                new RagService(
                        (question, limit) -> List.of(),
                        (instructions, input) -> {
                            throw new AssertionError("Model should not be called");
                        },
                        JSON,
                        0.45);
        assertThat(service.answer("unknown"))
                .isEqualTo(new RagService.Answer("NO_EVIDENCE", null, List.of()));
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
                        JSON,
                        0.45);
        assertThatThrownBy(() -> service.answer("question"))
                .isInstanceOf(ChatUnavailable.class)
                .hasMessage("CHAT_UNAVAILABLE")
                .hasNoCause();
    }

    @Test
    void streamingPublishesDeltasThenDoneAndCancelsUpstreamOnDispose() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var provider =
                new LlmProvider() {
                    @Override
                    public String answer(String instructions, String input) {
                        throw new AssertionError("SSE should use streaming provider");
                    }

                    @Override
                    public reactor.core.publisher.Flux<String> stream(
                            String instructions, String input) {
                        if (input.contains("cancel")) {
                            return reactor.core.publisher.Flux.<String>never()
                                    .doOnCancel(() -> cancelled.set(true));
                        }
                        return reactor.core.publisher.Flux.just("hello [S", "1]");
                    }
                };
        var source = match("source");
        var service = new RagService((question, limit) -> List.of(source), provider, JSON, 0.45);
        assertThat(service.stream("question").collectList().block(java.time.Duration.ofSeconds(2)))
                .containsExactly(
                        new RagService.StreamEvent("delta", "hello [S"),
                        new RagService.StreamEvent("delta", "1]"),
                        new RagService.StreamEvent(
                                "citations",
                                List.of(
                                        new RagService.Citation(
                                                "S1",
                                                source.documentId(),
                                                "test.txt",
                                                1,
                                                0,
                                                0,
                                                null,
                                                0,
                                                6))),
                        new RagService.StreamEvent("done", "ANSWERED"));
        var subscription = service.stream("cancel").subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
    }

    @Test
    void streamingProviderErrorEndsWithSafeEvent() {
        var provider =
                new LlmProvider() {
                    @Override
                    public String answer(String instructions, String input) {
                        throw new AssertionError("SSE should use streaming provider");
                    }

                    @Override
                    public reactor.core.publisher.Flux<String> stream(
                            String instructions, String input) {
                        return reactor.core.publisher.Flux.concat(
                                reactor.core.publisher.Flux.just("partial"),
                                reactor.core.publisher.Flux.error(
                                        new IllegalStateException("private detail")));
                    }
                };
        var service =
                new RagService((question, limit) -> List.of(match("source")), provider, JSON, 0.45);
        assertThat(service.stream("question").collectList().block(java.time.Duration.ofSeconds(2)))
                .containsExactly(
                        new RagService.StreamEvent("delta", "partial"),
                        new RagService.StreamEvent("error", "CHAT_UNAVAILABLE"));
    }

    @Test
    void citationsUseOnlySuppliedEvidenceAndNeverDuplicateRepeatedMarkers() {
        var first = match("first");
        var second = new Match(UUID.randomUUID(), "policy.pdf", 2, 1, 0, 4, 0, 6, "second", 0.1);
        var service =
                new RagService(
                        (question, limit) -> List.of(first, second),
                        (instructions, input) -> questionAnswer(input),
                        JSON,
                        0.45);
        var answer = service.answer("dedupe");
        assertThat(answer.citations())
                .containsExactly(
                        new RagService.Citation(
                                "S2", second.documentId(), "policy.pdf", 2, 1, 0, 4, 0, 6));
        assertThatThrownBy(() -> service.answer("forged")).isInstanceOf(ChatUnavailable.class);
        assertThat(service.answer("uncited").status()).isEqualTo("NO_EVIDENCE");
    }

    private static String questionAnswer(String input) {
        if (input.contains("forged")) {
            return "A fake source [S3].";
        }
        if (input.contains("uncited")) {
            return "Unsupported answer.";
        }
        return "Supported [S2]. Repeated [S2].";
    }

    @Test
    void skipsGenerationWhenRetrievedEvidenceIsBelowTheThreshold() {
        var distant =
                new Match(UUID.randomUUID(), "off-topic.txt", 1, 0, 0, null, 0, 6, "source", 0.8);
        var service =
                new RagService(
                        (question, limit) -> List.of(distant),
                        (instructions, input) -> {
                            throw new AssertionError("No supported answer");
                        },
                        JSON,
                        0.45);
        assertThat(service.answer("unknown").status()).isEqualTo("NO_EVIDENCE");
        assertThat(service.stream("unknown").collectList().block(java.time.Duration.ofSeconds(2)))
                .containsExactly(new RagService.StreamEvent("done", "NO_EVIDENCE"));
    }

    @Test
    void modelDeclineCannotPublishCitations() {
        var provider =
                new LlmProvider() {
                    @Override
                    public String answer(String instructions, String input) {
                        return "NO_EVIDENCE [S1]";
                    }

                    @Override
                    public reactor.core.publisher.Flux<String> stream(
                            String instructions, String input) {
                        return reactor.core.publisher.Flux.just("NO_", "EVIDENCE [S1]");
                    }
                };
        var service =
                new RagService((question, limit) -> List.of(match("source")), provider, JSON, 0.45);
        assertThat(service.answer("unrelated"))
                .isEqualTo(new RagService.Answer("NO_EVIDENCE", null, List.of()));
        assertThat(service.stream("unrelated").collectList().block(java.time.Duration.ofSeconds(2)))
                .containsExactly(
                        new RagService.StreamEvent("delta", "NO_"),
                        new RagService.StreamEvent("delta", "EVIDENCE [S1]"),
                        new RagService.StreamEvent("done", "NO_EVIDENCE"));
    }

    private static Match match(String text) {
        return new Match(UUID.randomUUID(), "test.txt", 1, 0, 0, null, 0, text.length(), text, 0.0);
    }
}
