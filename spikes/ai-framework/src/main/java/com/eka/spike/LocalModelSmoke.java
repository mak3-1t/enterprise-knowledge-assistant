package com.eka.spike;

import java.time.Duration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

/** Opt-in local smoke test. No company documents or external API keys are used. */
public class LocalModelSmoke {
    public static void main(String[] args) {
        String baseUrl = "http://localhost:11434";
        String model = "qwen3:4b-instruct-2507-q4_K_M";
        String prompt = "Say hello in Vietnamese in one short sentence.";
        var api = OllamaApi.builder().baseUrl(baseUrl).build();
        var chat =
                OllamaChatModel.builder()
                        .ollamaApi(api)
                        .options(
                                OllamaChatOptions.builder()
                                        .model(model)
                                        .temperature(0.0)
                                        .numPredict(64)
                                        .build())
                        .build();
        long start = System.nanoTime();
        String answer = chat.call(prompt);
        if (answer == null || answer.isBlank())
            throw new IllegalStateException("Empty Spring AI answer");
        System.out.printf("Spring AI: %s (%.2f s)%n", answer, (System.nanoTime() - start) / 1e9);
        var embedding =
                OllamaEmbeddingModel.builder()
                        .ollamaApi(api)
                        .options(OllamaEmbeddingOptions.builder().model("bge-m3").build())
                        .build();
        float[] vector = embedding.embed("EKA local framework smoke test");
        if (vector.length != 1024)
            throw new IllegalStateException("Unexpected dimension: " + vector.length);
        System.out.println("Spring AI embedding dimensions: " + vector.length);
        var other =
                dev.langchain4j.model.ollama.OllamaChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(model)
                        .temperature(0.0)
                        .numPredict(64)
                        .timeout(Duration.ofMinutes(3))
                        .build();
        start = System.nanoTime();
        String otherAnswer = other.chat(prompt);
        if (otherAnswer == null || otherAnswer.isBlank())
            throw new IllegalStateException("Empty LangChain4j answer");
        System.out.printf(
                "LangChain4j: %s (%.2f s)%n", otherAnswer, (System.nanoTime() - start) / 1e9);
    }
}
