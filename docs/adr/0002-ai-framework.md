# ADR-002: Use Spring AI for EKA

- Status: Accepted (local framework spike; production flows still require integration tests)
- Date: 2026-11-02
- Issue: #10

## Context
EKA is a Java 21 / Spring Boot 4.1.2 modular monolith. Choose a framework for RAG,
streaming and tool calling within a four-hour spike. Both frameworks meet the basic
feature requirements; integration cost is the deciding factor, not model intelligence.

## Comparison

| Criterion | Spring AI | LangChain4j |
|---|---|---|
| API stability | Pin 2.0.2; 2.0 supports Boot 4.0/4.1. Major upgrades change APIs (including Ollama options and tool loop). | Ollama 1.23.0 is published on Maven Central; other integration artifacts can use beta versions. Pin each artifact and verify availability. |
| pgvector | PgVectorStore with similarity search and metadata filters; Boot starter available. | PgVectorEmbeddingStore with similarity search and metadata filters. |
| Streaming | Reactive Flux through ChatModel/ChatClient, fits a future HTTP SSE adapter. | StreamingChatModel callbacks, plus high-level AI Services. |
| Tool calling | @Tool, ToolCallback and ChatClient tool loop; composes with Spring configuration. | @Tool and AI Services, or explicit low-level execution. |
| Documentation | Central reference and Boot configuration tables fit this project well. | Clear integration pages and tutorials; verify the exact artifact version rather than copying another integration's version. |
| EKA integration | Reuses existing Spring dependency injection and observation conventions. | Usable independently; adds a second set of application integration conventions. |

Documentation quality and integration cost above are engineering judgments, not benchmark results.

## Decision
Use Spring AI, with its BOM pinned to 2.0.2 when application integration begins.
Use spring-ai-starter-model-ollama for local development and
spring-ai-starter-vector-store-pgvector for the future retrieval adapter.
Keep framework types within adapters; module APIs should expose EKA DTOs/interfaces.
Do not add both frameworks to the application runtime. The comparison dependencies
remain in the standalone spikes/ai-framework project.

Use vector(1024) for bge-m3. Apply tenant and user authorization before retrieval and
again when serving sources. Metadata filters alone are not an authorization boundary.
Use schema migrations for production; do not rely on automatic vector-store schema creation.

## Evidence
Ran the standalone sample on 2026-11-02 with Java 21 and local Ollama:
- Spring AI 2.0.2: nonempty chat response; bge-m3 embedding exactly 1024 dimensions.
- LangChain4j Ollama 1.23.0: nonempty chat response using the same chat model.
- Chat model: qwen3:4b-instruct-2507-q4_K_M; temperature 0; maximum 64 generated tokens.
- Observed calls: Spring AI 7.14 s, LangChain4j 0.81 s. Calls were sequential and model
  loading/cache state differed. These numbers do NOT establish a framework speed ranking.

Streaming, tool execution and PostgreSQL round trips were reviewed in official docs,
not exercised by this smoke test. No claims about answer quality, tenant isolation,
end-to-end RAG correctness or cloud-model behavior follow from this sample.

## Consequences and follow-up
- Less integration code for the existing Boot application.
- Framework upgrades require compile checks and provider contract tests.
- Implement retrieval tests for tenant/ACL filtering, citations and no-answer behavior.
- Test streaming completion, cancellation, timeout and error propagation before exposing SSE.
- Test a deterministic tool contract before relying on local-model tool selection.
- Revisit if a measured requirement cannot be met by Spring AI.

## Official references (checked 2026-11-02)
- https://docs.spring.io/spring-ai/reference/getting-started.html
- https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html
- https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html
- https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- https://docs.spring.io/spring-ai/reference/api/tools.html
- https://docs.langchain4j.dev/integrations/language-models/ollama/
- https://docs.langchain4j.dev/integrations/embedding-stores/pgvector/
- https://docs.langchain4j.dev/tutorials/response-streaming/
- https://docs.langchain4j.dev/tutorials/tools/
- https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-ollama/maven-metadata.xml
