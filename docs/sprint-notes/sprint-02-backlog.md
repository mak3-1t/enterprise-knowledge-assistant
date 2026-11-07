# Sprint 2 proposed issue backlog — E2 ingestion

Updated: 2026-11-05. Original roadmap restored; [plan](sprint-02-planning.md).
These are original planning references, not issue numbers. Acceptance below refines
original intent; unchosen parameters remain explicit decisions before Ready.

## EKA-7 — Publish upload completion through transactional Outbox (5)

As a developer, I want committed upload completion to produce a durable event so
process crashes do not silently lose ingestion work.

- Given stored bytes and valid metadata, commit UPLOADED completion and event intent
  atomically; never publish an event for rolled-back/unavailable content.
- Given crash before/after publication/ack, restart eventually delivers committed
  work; duplicate delivery is expected and documented, not labeled exactly once.
- Version event schema and document identity/version; decide topics, partition key,
  claim/lease/retry policy, rollout of old uploads and safe observability before Ready.
- Real DB/Kafka tests prove crash boundaries, concurrent relay claims and tenant
  identity consistency. Add ADR comparing Outbox and dual-write.

Depends on Sprint 1 upload. No direct access to auth-owned tables.

## EKA-8 — Parse supported document formats with Tika (5)

As a user, I want uploaded documents converted to source text while bad input is isolated.

- Given allowed PDF/DOCX/TXT/MD fixtures, extract text and available source/page
  metadata; distinguish unsupported, corrupt, empty and scanned/no-text outcomes.
- Given corrupt/oversized/decompression-hostile input, enforce resource/time limits
  and safe failure; continue consuming other documents, without logging contents.
- Resolve storage only through ingestion adapter and document metadata; no arbitrary
  URL/path from event. Reject mismatched tenant/document/version before content access.
- Test fixture matrix, parse failures and independent subsequent successful message.

Depends on EKA-7 plus idempotency/retry contract from EKA-11.

## EKA-9 — Configurable chunk strategies and comparison (5)

As a developer, I want fixed/recursive strategies so chunk-size tradeoffs are measurable.

- Given strategy/size/overlap config, select via an interface and validate invalid
  config; output deterministic source mapping with no dropped/duplicated text bug.
- Test empty, multilingual, long, boundary and missing-page text; retain tenant/doc,
  source offsets and permissions needed by retrieval/citations.
- Compare candidate chunk sizes (e.g. 256/512/1024 tokens) using a recorded tokenizer,
  corpus and protocol; state actual metrics and limits, not a guessed best size.

Depends on EKA-8. Default size/overlap and experiment metric need refinement.

## EKA-10 — Batch embedding and tenant-owned pgvector records (5)

As a developer, I want searchable embeddings associated with safe source metadata.

- Batch with bounded memory/provider limits; validate vector dimension and finite
  values. Record embedding model/version; local bge-m3 uses vector(1024).
- Migrate schema explicitly; retain tenant/doc/version/chunk/source/permission metadata
  and uniqueness needed for replay. Define index based on verified compatibility.
- Failed partial batches cannot mark the document READY or expose partial results
  as complete. Define retry/replacement for model changes and document reprocessing.
- Deterministic CI adapter plus real pgvector integration verify persistence/scope;
  actual-model smoke test records model and dimensions separately.

Depends on EKA-9/EKA-11. Production provider/model not assumed from local measurements.

## EKA-11 — Idempotency, bounded retry and dead-letter handling (5)

As an operator, I want failures retried safely so replay cannot duplicate document chunks.

- Given duplicate/reordered events and concurrent delivery, only intended document
  version completes; chunks are not duplicated and stale work cannot overwrite newer work.
- Given transient/permanent failure, apply a defined bounded backoff/retry policy,
  then DLT with safe reason metadata. DLT replay is documented, authorized and idempotent.
- Test crashes/ack loss, lease recovery, interrupted embedding, delete/reprocess races
  and restart. Deleted/replaced documents must not be resurrected by late events.
- Expose bounded outcome metrics; do not put file contents, passwords/tokens or
  unbounded document/tenant IDs in metric labels.

Contract is designed alongside EKA-7 and implemented throughout EKA-8..10.

## EKA-12 — Tenant-authorized ingestion status (3)

As a user, I want processing status and safe failure reasons for my tenant's documents.

- Show UPLOADED -> PROCESSING -> READY or FAILED through a tenant-authorized API.
  READY requires all intended chunks/embeddings committed for the current version.
- Foreign/missing IDs are indistinguishable; safe reason codes omit stack traces,
  provider secrets, internal object keys and content. Roles follow approved read policy.
- Define retries, version/reprocess transitions and restart recovery; test stale events,
  inactive identity and both tenant directions.

Integrate throughout pipeline. No fabricated demo/retro results before live acceptance.

## Delivery

Refine actual GitHub issues/milestone/points, then one issue/branch/PR at a time.
Sprint ends with real pipeline demo, green required CI, chunk-size report, ADR and
updated docs before v0.2.0. Original IDs must never be used as Closes # numbers.

## Working implementation — 2026-11-06

Original EKA-7 has an implementation on feat/40-document-outbox-kafka: V7, lease-based
Outbox relay, versioned public event envelope and real DB/Kafka test coverage. Issue #40 tracks this delivery; PR/CI/merge are pending; see
[Outbox relay](../document-outbox-kafka.md). Parse/chunk/embed/READY remain later stories.
Sprint 1 OpenAPI/DoD closure tasks remain tracked; beginning this E2 work does not
claim Sprint 1 is fully closed or create a release/tag.

### EKA-7 verification checkpoint

Full developer verification passed 160 tests with zero failures/errors/skips, including
real DB/Kafka and live ClamAV (2026-11-06 17:34:39 +07:00). Issue #40 created; CI/merge are pending;
this does not complete EKA-8..12 or the remaining Sprint 1 DoD tasks.

## EKA-8 working implementation — 2026-11-06

Outbox issue #40 / PR #41 is merged (main b2a42ae). The next branch
feat/document-text-parser implements [durable parser intake/source persistence](../document-text-parser.md),
with 184 developer tests passed on 2026-11-07 at 10:48:29 +07:00, including PostgreSQL,
Kafka and live ClamAV. GitHub CI and merge are pending.
EKA-9 chunking and EKA-10 embedding/READY remain separate deliveries; original Sprint 1
OpenAPI/DoD gaps and Sprint 2 DLT/replay/acceptance are still open.
