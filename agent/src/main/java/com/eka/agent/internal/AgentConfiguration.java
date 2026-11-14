package com.eka.agent.internal;

import com.eka.agent.api.AgentService;
import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.HybridDocumentSearch;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "eka.chat.enabled", havingValue = "true")
class AgentConfiguration {
    @Bean(destroyMethod = "close")
    AgentService boundedAgentService(
            HybridDocumentSearch search,
            CurrentAccount current,
            ObjectMapper json,
            ObjectProvider<MeterRegistry> registries,
            @Value("${eka.chat.provider:ollama}") String provider,
            @Value("${eka.chat.base-url:http://localhost:11434}") URI endpoint,
            @Value("${eka.chat.model:qwen3:4b-instruct-2507-q4_K_M}") String model,
            @Value("${eka.agent.deadline-seconds:60}") long deadlineSeconds) {
        if (!provider.equals("ollama")
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || !model.matches("[A-Za-z0-9_./:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid agent provider configuration");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(25));
        var api =
                OllamaApi.builder()
                        .baseUrl(endpoint.toString())
                        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                        .build();
        ChatModel chat =
                OllamaChatModel.builder()
                        .ollamaApi(api)
                        .options(
                                OllamaChatOptions.builder()
                                        .model(model)
                                        .temperature(0.0)
                                        .numPredict(256)
                                        .build())
                        .build();
        ToolCallingManager manager =
                ToolCallingManager.builder()
                        .maxCallsPerTool(1)
                        .maxTotalToolCalls(1)
                        .resolutionFallbackEnabled(false)
                        .toolExecutionExceptionProcessor(
                                new DefaultToolExecutionExceptionProcessor(true))
                        .build();
        return new BoundedAgentService(
                chat,
                manager,
                search,
                current,
                json,
                registries.getIfAvailable(
                        () -> io.micrometer.core.instrument.Metrics.globalRegistry),
                Duration.ofSeconds(deadlineSeconds));
    }
}
