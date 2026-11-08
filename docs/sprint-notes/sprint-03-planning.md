# Sprint 3 plan

Sprint 3 starts from v0.2.0. The goal is a tenant-scoped chat that answers using
indexed documents, streams results and cites the source chunks.

| Story | Deliverable |
|---|---|
| EKA-13 | Exact pgvector search restricted to the authenticated tenant, current READY generation and matching embedding model |
| EKA-14 | Configurable LLM provider and a prompt assembled from bounded source context |
| EKA-15 | SSE streaming with cancellation and safe terminal errors |
| EKA-16 | Source identifiers, filename, real PDF page where available and validated chunk references |
| EKA-17 | Tenant-owned conversations with bounded history and restart persistence |
| EKA-18 | Configurable relevance threshold and an explicit no-answer path |

Use a minimal chat interface for acceptance. Prepare synthetic documents for two
tenants with conflicting facts, plus a separate evaluation set covering supported
and unsupported questions. Upload the source corpus through the normal ingestion
flow and keep expected answers outside the knowledge base.

## Retrieval checkpoint

The ingestion module exposes a Java retrieval contract for authenticated callers.
The query uses exact cosine distance with deterministic tie-breaking. It applies
tenant, READY state, source generation and model identity predicates before taking
the top ten results. Matches include bounded text slices and their original UTF-16
offsets. The adapter keeps source text out of its diagnostic string representation.
Exact search is the baseline for this sprint. Measure query latency before selecting
an approximate index for larger corpora.

The pgvector integration test covers both tenant directions, model version mismatch,
PROCESSING and deleted documents, result limits and Unicode source offsets. Run:

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-retrieval-verify.log
```

The original schedule allows two weeks. Reassess after the retrieval and provider
contracts are verified. Story IDs in this plan are internal planning references.

Developer verification passed 199 tests on 2026-11-08 at 09:38:19 +07:00, including tenant retrieval on pgvector and live ClamAV.

## EKA-14 provider and prompt checkpoint

PR #60 merged the tenant retrieval baseline at 953d5d1. The opt-in chat endpoint
accepts a JSON question through POST /api/chat. It uses the same embedding model and
version as ingestion, limits retrieval to five chunks and includes up to 12,000 UTF-16
code units of evidence in a structured prompt. System instructions and evidence use
separate message roles. Empty retrieval returns NO_EVIDENCE without a chat model call.
Provider failures return CHAT_UNAVAILABLE without exposing diagnostic details.

Enable eka.chat.enabled and eka.ingestion.embedding.enabled and set the required
embedding model version. Chat configuration supports eka.chat.provider (ollama),
eka.chat.base-url (local Ollama by default) and eka.chat.model (the local Qwen model
from Sprint 0 by default). Use an approved model endpoint for tenant document text.
The initial endpoint is synchronous. Streaming, citation validation, persistence and
a calibrated relevance threshold are tracked separately. Prompt instructions alone
do not guarantee model grounding.

Verify with the full Docker suite:

    & .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-rag-verify.log

Developer verification passed 202 tests on 2026-11-08 at 09:51:42 +07:00, including RAG over authenticated HTTP. The app test used a deterministic Ollama endpoint. A separate local Qwen API smoke answered a synthetic question correctly.

## EKA-15 streaming checkpoint

PR #62 merged the synchronous chat provider at 3d26be1. Authenticated POST
/api/chat/stream accepts the same JSON question and returns text/event-stream.
Use fetch with the bearer token and AbortController for cancellation.

Events use delta for text and done for ANSWERED or NO_EVIDENCE. An upstream failure
after streaming starts emits error with CHAT_UNAVAILABLE and ends the response.
The endpoint sends comment heartbeats every five seconds to detect closed connections.
When the servlet detects a failed write or timeout, cancellation propagates to the
upstream subscription. The provider stream has a 90-second deadline and the existing
output size limit. Embedding and retrieval finish in the authenticated request before
the stream starts, so heartbeat and cancellation apply to generation.

The developer suite exercises provider cancellation, ordered deltas, safe errors and
the authenticated SSE HTTP contract using a deterministic Ollama endpoint.

    & .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-sse-verify.log

Developer verification passed 204 tests on 2026-11-08 at 20:15:15 +07:00, including authenticated SSE, ordered deltas, no-evidence completion and provider subscription cancellation.

## EKA-16 citation checkpoint

PR #64 merged SSE at 17b0d7a. Chat assigns request-local IDs such as S1 only to
chunks that fit the prompt. The model refers to them with bracketed markers.
The server resolves used IDs against that request's tenant-scoped retrieval results.
Response citations contain document ID, filename, source generation, chunk and section
ordinals, a nullable PDF page, and half-open UTF-16 offsets within the section.

POST /api/chat includes a citations array. POST /api/chat/stream emits a citations
event with the list in its value field before done ANSWERED. Deltas are provisional
until done. Clients must discard them on error or done NO_EVIDENCE. An unrecognized
marker fails safely, repeated IDs are deduplicated, and an answer without markers
cannot be marked ANSWERED. A recognized ID verifies the source link. It does not
verify that the cited passage logically entails every sentence.

    & .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-citations-verify.log

Developer verification passed 205 tests on 2026-11-08 at 20:28:33 +07:00, including citation mapping on authenticated HTTP and SSE.

## EKA-17 conversation checkpoint

PR #66 merged citations at 4c6767f. Flyway V10 adds private conversations and
atomic question/response turns. All routes require the authenticated owner and tenant.
A missing or inaccessible conversation returns the same 404.

- POST /api/chat/conversations creates a conversation and returns its id and revision.
- POST /api/chat/conversations/{id}/turns accepts question and expectedRevision.
  A stale revision returns 409. The model call runs outside the write transaction.
  Only one competing request at the same revision can commit.
- GET /api/chat/conversations/{id} returns up to 20 newest turns. Use the oldest
  returned revision as the exclusive before cursor. A turn response is null if a
  cited document is no longer READY at the cited generation.

The prompt can include up to six prior questions with a total budget of 4,000
UTF-16 code units. Previous answers stay out of the model prompt because their
sources can change. Retrieval still uses the current question. Conversation
writes use the synchronous endpoint. Existing SSE remains stateless.

    & .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-conversations-verify.log

Developer verification passed 205 tests on 2026-11-08 at 21:24:52 +07:00, including Flyway V10, authenticated conversation turns, stale revision rejection and response redaction after source deletion.
