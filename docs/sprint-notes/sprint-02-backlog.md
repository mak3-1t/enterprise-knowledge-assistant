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
