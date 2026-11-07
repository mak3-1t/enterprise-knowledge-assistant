# Durable document text parsing (Tika)

Status: developer integration verification passed. GitHub CI and merge are pending. Original Sprint 2 planning reference EKA-8
is not a GitHub issue number. This delivery ends at private parsed sources, not READY.

## Flow

CONTENT_VERIFIED in Kafka -> transactional inbox -> leased parse job -> immutable
snapshot/hash check -> child Tika process -> atomic private sections + SOURCE_PARSED.
The consumer validates schema, canonical identity, key, timestamp and persisted Outbox
provenance. Current tenant/document must be PROCESSING at the matching version before
acceptance. Only CONTENT_VERIFIED can create parse work. No event-provided URL/path is
accepted. Known other event types are ignored; invalid/unverified/stale records leave
only coordinates, SHA-256 and a safe reason in the local rejection ledger.

Kafka auto-commit is disabled. RECORD acknowledgement occurs after the inbox DB call
commits. A database outage retries without advancing that offset. Crash after inbox
commit before offset commit can redeliver; the stable event ID and document generation
are unique, so delivery cannot duplicate work. The local rejection ledger is not a
Kafka DLT; retention, authorized replay and Kafka DLT delivery remain EKA-11 work.

Workers claim with SKIP LOCKED and a random five-minute lease. Four attempts total
(initial + three retries with 5/10/20-second delay); expired leases recover crashes.
A fourth crashed lease becomes DEAD/FAILED. Permanent empty/corrupt/unsupported results
fail immediately. No failed or stale result becomes a chunking input.

Source sections, source-generation pointer, document version, SOURCE_PARSED Outbox
and job success commit together. Outbox failure rolls back all of them. The document
stays PROCESSING. Parse completion increments its version; the original parse generation
remains the private source pointer. Downstream chunk workers must validate the new
SOURCE_PARSED document version and load only that document's current source pointer.
Delete removes derived text and invalidates the pointer immediately, while retaining
the original according to the approved seven-day policy. Restore requires a new scan
and parse generation. Old worker results cannot resurrect deleted/restored sources.

## Extraction and source mapping

Tika core/PDF/Microsoft/text modules are pinned to the maintained 3.3.3 line. Parser
selection is explicit for PDF/DOCX/TXT/MD; embedded attachment parsing, OCR and external
converters are disabled. Structural gates run again before parsing; this does not
replace a successful malware scan. The child has 256 MiB heap and a 60-second deadline,
drops app credentials from its environment and discards stdout/stderr diagnostics.

PDF sections retain actual page indices, including gaps for blank pages. DOCX/TXT/MD
have no invented page numbers. Normalize Unicode NFC and line endings; Markdown remains
unrendered source text, including code/examples. No document text is used as model or
system instructions here. Source offsets for future chunks refer to normalized text,
not original binary byte offsets. Blank/image-only PDFs without OCR report EXTRACT_EMPTY.

Bounds: 20 MiB original input (existing upload policy), two million extracted characters,
2,000 PDF pages/sections and 8 MiB child JSON output. Over-limit output rejects rather
than silently truncating. Parent validates the output before private DB persistence.
Typed source records redact text in toString; no public extracted-text route is added.
Internal loadCurrent requires explicit tenant/document scope and current state; that
repository scope is not a substitute for authentication at a future HTTP boundary.

## Runtime configuration

Defaults remain off. Enable EKA_DOCUMENT_PARSER_ENABLED=true only with private upload
storage and Kafka relay configured (EKA_UPLOAD_ENABLED=true and
EKA_INGESTION_KAFKA_ENABLED=true). The existing scan worker must produce verified events;
it may run in the same process or already have produced historical verified work.
Consumer group defaults eka-document-parser-v1; override EKA_DOCUMENT_PARSER_GROUP if
required. Topic remains the configured document events topic; SOURCE_PARSED is an
additive event type in schema v1. Parser workers have a separate bounded scheduler.

Safe failures include EXTRACT_EMPTY, EXTRACT_LIMIT, CORRUPTED_FILE, TYPE_MISMATCH,
PASSWORD_PROTECTED, PARSE_UNAVAILABLE, PROCESSING_TIMEOUT and PARSE_RETRY_EXHAUSTED.
HTTP metadata exposes the existing safe errorCode/status, not parsed text or diagnostics.
Operational events parse_succeeded/parse_deferred/parse_failed have bounded labels;
UUIDs appear only in logs. DB job state remains the authoritative failure ledger.

## Verification

The full V8 developer suite passed 184 tests with zero failures, errors or skips on
2026-11-07 at 10:48:29 +07:00, including PostgreSQL, Kafka and live ClamAV.

Unit fixtures cover Vietnamese text, Markdown/code, real PDF pages, DOCX paragraphs,
empty/no-text outcomes, size cap, disguised inputs, child-JVM extraction, event schema,
key/provenance validation and worker error handling. PostgreSQL tests cover idempotent
inbox/source publication, lease recovery, rollback, tenant isolation and delete/restore
fencing. Real Kafka/DB tests verify durable inbox/offset recovery and duplicate delivery.
ParserApplicationTests enables the actual application relay/listener/worker and child
parser, with fake storage and a seeded verified scan; S3/ClamAV engines have separate
integration tests. This is not yet a packaged full-product acceptance/demo claim.

Run from the repository with Docker Desktop running:

    & "C:\Users\Thang\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe" -NoProfile -File .\scripts\verify-document-parser.ps1

Log: target/document-parser-verify.log. The helper enables the live ClamAV test. V1..V7 remain unchanged;
new V8 creates jobs/source/rejection tables and the source pointer. No pgvector/model
or cloud credentials are needed for deterministic parser verification.

## Remaining work

No chunking/embedding/search/READY publication is delivered here. Complete OS/container
filesystem/network/CPU isolation is still pending: child heap/deadline isolation is
not a full sandbox. Temporary outputs are deleted on handled exits; host/process crash
cleanup needs operational retention. Full broker DLT/replay, alerting, event/job retention,
full daily storage reconciliation, resumable/frontend flows and remaining Sprint 1
OpenAPI/DoD gates remain tracked. Do not mark the whole sprint or product complete.

References:
- https://tika.apache.org/download.html
- https://tika.apache.org/security.html
