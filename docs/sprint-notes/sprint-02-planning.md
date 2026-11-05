# Sprint 2 planning — asynchronous ingestion

Updated: 2026-11-05. Restores original E2 scope from the developer's roadmap/Claude
handover; replaces the unapproved document-storage proposal. Upload/list belongs to
[Sprint 1 completion](sprint-01-completion.md). See [roadmap](../roadmap.md).

## Goal and entry criteria

An uploaded document is parsed, chunked, embedded and persisted for retrieval through
a reliable asynchronous pipeline. Complete/verify Sprint 1 upload, identity and role
boundaries before implementation; an auth-only demo is insufficient entry evidence.

Kafka and pgvector infrastructure exist; application pipeline code does not. Local
embedding baseline is bge-m3/1024 dimensions; production model choice is still open.
Use Spring AI within adapters per merged ADR-002 and keep module APIs framework-free.

## Original scope

| Reference | Story | Original points | Dependency |
|---|---|---|---|
| EKA-7 | Transactional Outbox and upload-completed event | 5 | Sprint 1 upload |
| EKA-8 | Apache Tika parser: PDF/DOCX/TXT/MD | 5 | EKA-7 |
| EKA-9 | Configurable fixed/recursive chunk strategies | 5 | EKA-8 |
| EKA-10 | Batch embedding and pgvector metadata | 5 | EKA-9 |
| EKA-11 | Idempotent consumer, retry and DLT | 5 | Event/consumer design; implement before accepting reliability |
| EKA-12 | UPLOADED/PROCESSING/READY/FAILED status API | 3 | Integrate throughout pipeline |

Original total: 28 points, not proven capacity or velocity. Stories are planning IDs,
not GitHub issues. Refine <=8-point stories with Given/When/Then acceptance before Ready.
Retry/idempotency and status are integrated as the pipeline is built; their table
order does not permit an unreliable consumer to be considered complete.

## Boundaries and contracts to refine

- Ingestion owns document, processing and chunk/outbox records. Authenticate caller
  through auth public API. Event tenant IDs must be verified against owned document
  metadata; Kafka payload is not an authority to bypass tenant/ACL checks.
- Upload means persisted bytes + metadata (UPLOADED), not vector readiness. Persist
  upload completion and outbox intent atomically when the outbox story is introduced;
  define rollout/reconciliation for earlier Sprint 1 uploads explicitly.
- Outbox delivery is at least once; consumers must be idempotent. Do not promise
  exactly-once external embedding/storage transactions.
- Decide versioned event schema, event/document/tenant IDs, document version,
  partition key, topics/groups, retry/backoff/DLT policy and safe reason codes.
- Chunk records retain tenant, document, source position/page and permission metadata
  for later citations/ACL. ACL granularity remains a separate product decision.
- Choose default chunk size/overlap from documented experiments; 256/512/1024 token
  sizes are comparison candidates, not fixed requirements or measured winners.
- Schema/index migration, model/dimension identity, batching limits, provider failure,
  document deletion during work and reprocessing cleanup need explicit contracts.
- Enforce parser resource/time limits and untrusted-file handling. Failed documents
  must not crash the consumer, expose contents in errors or become READY prematurely.

## Verification and deliverables

Test real disposable PostgreSQL/pgvector, Kafka and compatible storage; use deterministic
embedding doubles for routine CI and an opt-in actual-model smoke test with evidence.
Unit-test chunk strategies. Integration-test crash boundaries, duplicate/out-of-order
messages, partial batches, restart/replay, retry exhaustion/DLT and tenant substitution.
Retain all auth and upload tests; no silently skipped integration tests.

Deliverable: v0.2.0 after acceptance, ADR Outbox vs dual-write and a reproducible chunk
size comparison recording model, corpus, settings and observed results. No fabricated
benchmark or automatic claim that a nonempty embedding proves retrieval quality.
RAG/SSE/citations are Sprint 3; hybrid/reranking/ACL enforcement and agents Sprint 4.
Dates and additional scope changes require developer agreement.
