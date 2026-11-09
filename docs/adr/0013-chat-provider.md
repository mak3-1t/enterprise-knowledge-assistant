# ADR-013: Isolate chat generation behind LlmProvider

- Status: Accepted for Sprint 3
- Date: 2026-11-09

## Context

Chat needs synchronous generation and cancellable streaming from the same prompt.
Retrieval, citation validation and conversation persistence must remain independent
of the model integration. ADR-002 selects Spring AI for the adapter.

## Decision

Keep a small LlmProvider interface inside the chat module. The RAG service prepares
the tenant-scoped evidence and validates citation IDs. The Ollama adapter translates
the system and user messages into Spring AI requests and exposes answer text or
streaming deltas. Enable chat explicitly and select the provider and model in
deployment configuration. Ollama is the supported adapter today. Unknown provider
configuration fails at startup.

Use the ingestion embedding model and version for question retrieval. Recognize
NO_EVIDENCE as a distinct result before publishing citations. Keep model diagnostics
out of HTTP responses. On SSE cancellation, dispose of the model subscription after
the server detects the disconnect.

## Consequences

Deterministic tests can supply a provider without a local model. A second provider
must implement and verify the same generation and cancellation behavior. Grounding
quality depends on the model and corpus. Retry, fallback and production limits
remain later roadmap work.
