package com.eka.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.auth.api.AuthenticatedAccount;
import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.DocumentRetrieval.Match;
import com.eka.ingestion.api.HybridDocumentSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.json.JsonMapper;

class BoundedAgentServiceTest {
    private static final AuthenticatedAccount ACCOUNT =
            new AuthenticatedAccount(UUID.randomUUID(), UUID.randomUUID(), "ADMIN");

    @Test
    void allowsOnlyBoundedTenantSearchAndReturnsValidatedCitations() {
        var model = mock(ChatModel.class);
        var manager = toolManager("{\"question\":\"remote work\"}", "two days each week");
        var search = mock(HybridDocumentSearch.class);
        when(search.search("remote work", 5)).thenReturn(List.of(match()));
        when(model.call(any(Prompt.class)))
                .thenReturn(
                        toolRequest(), finalAnswer("Employees may work remotely two days [S1]."));
        var service = service(model, manager, search, Duration.ofSeconds(2));

        var answer = service.answer("How many remote days?");

        assertThat(answer.status()).isEqualTo("ANSWERED");
        assertThat(answer.answer()).contains("two days");
        assertThat(answer.citations())
                .extracting(com.eka.agent.api.AgentService.Citation::filename)
                .containsExactly("policy.md");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues())
                .allSatisfy(
                        prompt ->
                                assertThat(((OllamaChatOptions) prompt.getOptions()).getModel())
                                        .isEqualTo("qwen3:4b-instruct-2507-q4_K_M"));
    }

    @Test
    void rejectsToolArgumentsThatTryToSupplyTenantIdentity() {
        var model = mock(ChatModel.class);
        var manager = toolManager("{\"question\":\"policy\",\"tenantId\":\"other\"}", "");
        var search = mock(HybridDocumentSearch.class);
        when(model.call(any(Prompt.class))).thenReturn(toolRequest());
        var service = service(model, manager, search, Duration.ofSeconds(2));

        assertThatThrownBy(() -> service.answer("policy")).isInstanceOf(AgentUnavailable.class);
        org.mockito.Mockito.verifyNoInteractions(search);
    }

    @Test
    void fallsBackToServerCitedExcerptWhenModelOmitsCitation() {
        var model = mock(ChatModel.class);
        var manager = toolManager("{\"question\":\"remote work\"}", "two days each week");
        var search = mock(HybridDocumentSearch.class);
        when(search.search("remote work", 5)).thenReturn(List.of(match()));
        when(model.call(any(Prompt.class)))
                .thenReturn(toolRequest(), finalAnswer("Employees may work remotely."));
        var service = service(model, manager, search, Duration.ofSeconds(2));

        var answer = service.answer("How many remote days?");

        assertThat(answer.status()).isEqualTo("ANSWERED");
        assertThat(answer.answer()).isEqualTo("two days each week [S1]");
        assertThat(answer.citations())
                .extracting(com.eka.agent.api.AgentService.Citation::filename)
                .containsExactly("policy.md");
    }

    @Test
    void preservesExplicitNoEvidenceEvenWhenSearchReturnsEvidence() {
        var model = mock(ChatModel.class);
        var manager = toolManager("{\"question\":\"remote work\"}", "two days each week");
        var search = mock(HybridDocumentSearch.class);
        when(search.search("remote work", 5)).thenReturn(List.of(match()));
        when(model.call(any(Prompt.class))).thenReturn(toolRequest(), finalAnswer("NO_EVIDENCE"));
        var service = service(model, manager, search, Duration.ofSeconds(2));

        var answer = service.answer("How many remote days?");

        assertThat(answer.status()).isEqualTo("NO_EVIDENCE");
        assertThat(answer.answer()).isNull();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void enforcesRequestDeadlineAndQuestionLength() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            Thread.sleep(100);
                            return finalAnswer("late");
                        });
        var service =
                service(
                        model,
                        mock(ToolCallingManager.class),
                        mock(HybridDocumentSearch.class),
                        Duration.ofMillis(10));

        assertThatThrownBy(() -> service.answer("question")).isInstanceOf(AgentUnavailable.class);
        assertThatThrownBy(() -> service.answer("x".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_QUESTION");
    }

    @Test
    void boundsToolExecutionAndRejectsUnverifiedCitationIds() {
        var model = mock(ChatModel.class);
        var search = mock(HybridDocumentSearch.class);
        when(search.search("policy", 5))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(500);
                            return List.of(match());
                        });
        when(model.call(any(Prompt.class))).thenReturn(toolRequest());
        var timedService =
                service(
                        model,
                        toolManager("{\"question\":\"policy\"}", ""),
                        search,
                        Duration.ofMillis(10));

        assertThatThrownBy(() -> timedService.answer("policy"))
                .isInstanceOf(AgentUnavailable.class);

        var citationService =
                service(
                        model,
                        toolManager("{\"question\":\"policy\"}", "two days"),
                        search,
                        Duration.ofSeconds(2));
        when(search.search("policy", 5)).thenReturn(List.of(match()));
        when(model.call(any(Prompt.class)))
                .thenReturn(toolRequest(), finalAnswer("Unsupported claim [S9]."));

        assertThatThrownBy(() -> citationService.answer("policy"))
                .isInstanceOf(AgentUnavailable.class);
    }

    private static BoundedAgentService service(
            ChatModel model,
            ToolCallingManager manager,
            HybridDocumentSearch search,
            Duration deadline) {
        CurrentAccount current = () -> ACCOUNT;
        return new BoundedAgentService(
                model,
                "qwen3:4b-instruct-2507-q4_K_M",
                manager,
                search,
                current,
                JsonMapper.builder().build(),
                new SimpleMeterRegistry(),
                deadline);
    }

    private static ToolCallingManager toolManager(String args, String expectedEvidence) {
        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(
                        invocation -> {
                            Prompt prompt = invocation.getArgument(0);
                            ToolCallingChatOptions options =
                                    (ToolCallingChatOptions) prompt.getOptions();
                            String output = options.getToolCallbacks().get(0).call(args);
                            assertThat(output).contains(expectedEvidence);
                            return new DefaultToolExecutionResult(
                                    List.of(new UserMessage(output)), false);
                        })
                .when(manager)
                .executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        return manager;
    }

    private static ChatResponse toolRequest() {
        var call =
                new AssistantMessage.ToolCall(
                        "call-1", "function", "search_documents", "{\"question\":\"remote work\"}");
        var message = AssistantMessage.builder().toolCalls(List.of(call)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse finalAnswer(String answer) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private static Match match() {
        return new Match(
                UUID.randomUUID(),
                "policy.md",
                1,
                0,
                0,
                null,
                0,
                23,
                "two days each week",
                0.1,
                false);
    }
}
