package com.eka.ingestion.internal.embedding;

import com.eka.auth.api.CurrentAccount;
import com.eka.ingestion.api.DocumentReranker;
import com.eka.ingestion.api.DocumentRetrieval;
import com.eka.ingestion.api.HybridDocumentSearch;
import com.eka.ingestion.api.SemanticDocumentSearch;
import com.eka.ingestion.internal.chunking.DocumentChunkService;
import com.eka.ingestion.internal.retrieval.LexicalSectionSearch;
import com.eka.ingestion.internal.retrieval.RerankingEvents;
import com.eka.ingestion.internal.retrieval.RrfDocumentSearch;
import com.eka.ingestion.internal.retrieval.TeiDocumentReranker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    SemanticDocumentSearch semanticDocumentSearch(
            CurrentAccount account,
            DocumentRetrieval retrieval,
            EmbeddingBatch embedder,
            EmbeddingSettings settings) {
        return (question, limit) -> {
            account.require();
            if (question == null
                    || question.isBlank()
                    || question.length() > 2000
                    || limit < 1
                    || limit > 10) {
                throw new IllegalArgumentException("Invalid semantic search input");
            }
            return retrieval.search(
                    embedder.embedQuestion(question),
                    settings.model(),
                    settings.modelVersion(),
                    limit);
        };
    }

    @Bean
    HybridDocumentSearch hybridDocumentSearch(
            SemanticDocumentSearch semantic,
            LexicalSectionSearch lexical,
            EmbeddingSettings settings,
            @Value("${eka.ingestion.retrieval.hybrid-enabled:true}") boolean enabled,
            @Value("${eka.ingestion.retrieval.reranking-enabled:false}") boolean rerankingEnabled,
            @Value("${eka.ingestion.retrieval.reranker.endpoint:http://localhost:8081}")
                    String endpoint,
            ObjectProvider<MeterRegistry> registries) {
        var events = new RerankingEvents(registries);
        DocumentReranker reranker =
                rerankingEnabled
                        ? new TeiDocumentReranker(
                                RestClient.builder()
                                        .baseUrl(
                                                TeiDocumentReranker.validateEndpoint(endpoint)
                                                        .toString())
                                        .requestFactory(
                                                requestFactory(
                                                        Duration.ofSeconds(3),
                                                        Duration.ofSeconds(15)))
                                        .build())
                        : null;
        return new RrfDocumentSearch(
                semantic,
                lexical,
                settings.model(),
                settings.modelVersion(),
                enabled,
                reranker,
                rerankingEnabled,
                events);
    }

    @Bean
    EmbeddingBatch documentEmbeddingBatch(EmbeddingSettings settings) {
        var api =
                OllamaApi.builder()
                        .baseUrl(settings.baseUrl().toString())
                        .restClientBuilder(
                                RestClient.builder()
                                        .requestFactory(
                                                requestFactory(
                                                        Duration.ofSeconds(5),
                                                        Duration.ofSeconds(60))))
                        .build();
        return new EmbeddingBatch(
                new OllamaDocumentEmbeddingProvider(api, settings.model()), settings);
    }

    private static SimpleClientHttpRequestFactory requestFactory(
            Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
