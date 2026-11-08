package com.eka.chat.internal;

import com.eka.ingestion.api.SemanticDocumentSearch;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "eka.chat.enabled", havingValue = "true")
class RagConfiguration implements WebMvcConfigurer {
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(Duration.ofSeconds(95).toMillis());
    }

    @Bean
    LlmProvider ragLlmProvider(
            @Value("${eka.chat.provider:ollama}") String provider,
            @Value("${eka.chat.base-url:http://localhost:11434}") URI endpoint,
            @Value("${eka.chat.model:qwen3:4b-instruct-2507-q4_K_M}") String model) {
        if (!provider.equals("ollama")
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || !model.matches("[A-Za-z0-9_./:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid chat provider configuration");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        var api =
                OllamaApi.builder()
                        .baseUrl(endpoint.toString())
                        .restClientBuilder(RestClient.builder().requestFactory(factory))
                        .build();
        var chat =
                OllamaChatModel.builder()
                        .ollamaApi(api)
                        .options(
                                OllamaChatOptions.builder()
                                        .model(model)
                                        .temperature(0.0)
                                        .numPredict(256)
                                        .build())
                        .build();
        return new LlmProvider() {
            @Override
            public String answer(String instructions, String input) {
                return chat.call(prompt(instructions, input)).getResult().getOutput().getText();
            }

            @Override
            public Flux<String> stream(String instructions, String input) {
                return chat.stream(prompt(instructions, input))
                        .handle(
                                (response, sink) -> {
                                    var result = response.getResult();
                                    if (result != null && result.getOutput() != null) {
                                        String delta = result.getOutput().getText();
                                        if (delta != null && !delta.isEmpty()) {
                                            sink.next(delta);
                                        }
                                    }
                                });
            }
        };
    }

    private static Prompt prompt(String instructions, String input) {
        return new Prompt(List.of(new SystemMessage(instructions), new UserMessage(input)));
    }

    @Bean
    RagService ragService(SemanticDocumentSearch search, LlmProvider model, ObjectMapper json) {
        return new RagService(search, model, json);
    }
}
