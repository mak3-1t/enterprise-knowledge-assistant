package com.eka.ingestion.internal.embedding;

import com.eka.ingestion.internal.chunking.DocumentChunkService;
import java.time.Duration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "eka.ingestion.embedding", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(EmbeddingSettings.class)
class EmbeddingConfiguration {
    @Bean
    ThreadPoolTaskScheduler embeddingScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("document-embedding-");
        return scheduler;
    }

    @Bean
    EmbeddingWorker embeddingWorker(
            EmbeddingJobRepository jobs,
            DocumentChunkService chunks,
            EmbeddingBatch embedder,
            EmbeddingSettings settings) {
        return new EmbeddingWorker(jobs, chunks, embedder, settings);
    }

    @Bean
    EmbeddingBatch documentEmbeddingBatch(EmbeddingSettings settings) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        var api =
                OllamaApi.builder()
                        .baseUrl(settings.baseUrl().toString())
                        .restClientBuilder(RestClient.builder().requestFactory(factory))
                        .build();
        return new EmbeddingBatch(
                new OllamaDocumentEmbeddingProvider(api, settings.model()), settings);
    }
}
