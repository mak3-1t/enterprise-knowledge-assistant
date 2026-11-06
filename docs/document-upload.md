# Document upload implementation and verification

Status: working branch, not merged. V4 metadata + V5 lifecycle and private S3 upload/API
are implemented; the full V6 suite passed 142 tests locally on 2026-11-06.
See [approved contract](document-upload-contract.md) for the complete target.

## Implemented

- Authenticated ADMIN/EDITOR initiate and complete; VIEWER cannot mutate. Every
  tenant/user comes from CurrentAccount. All roles can list/read own-tenant metadata.
- One PDF/DOCX/TXT/MD per upload, 1..20 MiB. Legacy DOC/DOCM rejected by allowlist.
  Filename rejects paths, controls, bidi/format characters and Windows ADS colon.
- Per-tenant/user reservation: 500 MiB and 100 files. Row locking serializes quota
  admission. Same Idempotency-Key (8-128 ASCII letters/digits/underscore/hyphen) and
  payload returns the same intent; changed payload conflicts; a new key creates a
  separate document. Keys remain bound to tombstones and cannot be recycled.
- S3 SigV4 POST form expires after at most ten minutes, bounded by intent's one-hour
  expiry. Exact object key, Content-Type and declared byte size are signed. This is
  multipart/form-data POST, not S3 multipart/resumable upload. The file field goes last.
- Complete locks the row and reads bounded staging bytes, measures SHA-256, checks
  optional client checksum, and writes an immutable private original with conditional
  PUT. Subsequent staging writes cannot change the original. DB completion and one
  Outbox event commit together. A retry returns recorded completion without storage I/O.
- Metadata lists expose status, never storage keys or checksums. UPLOADED is not READY;
  download returns 409 until the actual processing gates publish READY.
- Delete is immediately hidden. Uploaded original is retained seven days. Restore
  returns UPLOADED, requiring fresh processing. Cancelled pending intents cannot restore.
  Cancellation retains quota until intent expiry so the live upload form cannot
  immediately bypass quota. Immediate revocation of an already issued S3 form is not promised.
- Hourly expiry/cleanup, bounded batches, storage timeout and retry on next maintenance
  pass. Storage deletion runs under the document lock to serialize purge/restore.
  Quota is released once, after storage confirms both known keys removed. Failed I/O
  leaves reservation/tombstone retryable. SDK error details are not exposed to clients.
- Download gateway checks tenant/READY on each request and sends attachment, nosniff
  and no-store. Already in-flight reads may finish. No signed download capability leaks.

## Enable locally

Upload routes are disabled by default while the full ingestion pipeline is unfinished.
Create a dedicated private, unversioned bucket `eka-documents` in the development
S3 console. Anonymous access must remain disabled. Configure staging/ lifecycle expiry
24h and incomplete multipart abort 1-2 days. Do not apply that rule to tenants/ originals.
Browser upload needs CORS allowing the exact frontend origin and POST; no wildcard
credentialed origin. Integration tests use an isolated disposable bucket/container.

Set EKA_UPLOAD_ENABLED=true, EKA_UPLOAD_ENDPOINT=http://localhost:9000,
EKA_UPLOAD_PUBLIC_ENDPOINT=http://localhost:9000, EKA_UPLOAD_REGION=us-east-1,
EKA_UPLOAD_BUCKET=eka-documents, EKA_UPLOAD_ACCESS_KEY and EKA_UPLOAD_SECRET_KEY.
Do not commit credentials. Use dedicated credentials with only this bucket's required
GET/PUT/DELETE permissions; production public endpoint requires HTTPS. App does not
silently create buckets, widen ACLs, or overwrite existing CORS/lifecycle policies.
Static credentials currently target local S3; workload-identity credentials and
version-aware retention must precede a production deployment using those features.

## HTTP contract

All responses use no-store; all routes require Bearer authentication.

| Method | Route | Result |
|---|---|---|
| POST | /api/documents/uploads | JSON filename, sizeBytes, optional sha256; Idempotency-Key header; 200 document + upload form |
| POST | /api/documents/{id}/complete | 200 metadata; storage measured, never client status/key |
| GET | /api/documents?page=0&size=20 | tenant list, size 1..100, bounded integer offset |
| GET | /api/documents/{id} | metadata; foreign/missing/deleted/expired -> 404 |
| DELETE | /api/documents/{id} | ADMIN any own-tenant; EDITOR own; 204, repeated delete safe |
| POST | /api/documents/{id}/restore | eligible retained own/ADMIN; 200 UPLOADED |
| GET | /api/documents/{id}/download | READY only; attachment stream |

POST form response contains URL, fields and expiresAt. Send each field unchanged and
append the binary `file` part last. A successful storage response must be followed by
complete. Client MIME is not proof of content. Conflicting idempotency/quota/state ->
409; oversized ->413; unsupported extension ->415; denied role ->403; foreign ->404;
storage/DB failure ->503 generic. Signature-policy rejection comes directly from S3.

## Content scan follow-up and remaining work

[V6 content validation and ClamAV worker](document-scan.md) now extend this slice,
verified in the 142-test local run. The earlier 114-test result covers V4/V5 only.
New scan tests, full-suite expectations and the real-engine verification command are
recorded in that document. Scanning success means PROCESSING, never READY.

Parser/chunk/embedding generation publication, complete OS sandbox restrictions,
Kafka publication/consumption/DLT, full daily storage reconciliation, resumable
transfer and frontend progress remain pending. Daily reconciliation must catch late
POSTs after cleanup; staging lifecycle is only a fallback. Restore re-enters scanning.
Do not mark the full upload contract or original Sprint 1 complete yet.

## Verification

Agent run: compile all modules/test sources, selected unit regression, Spotless and
Checkstyle. Full verify requires Docker and downloads the current development S3 image.
V4/V5 baseline: 114 tests passed; current V6 suite is described in document-scan.md. Auth demo remains 25 checks with migrations V1..V6.
This count is an expectation until a real full run passes.

    .\mvnw.cmd -B verify

New integration suites exercise real PostgreSQL quota/idempotency races, transaction
rollback on injected insert/Outbox errors, expiry, retention, failure/purge, role and
both tenant directions; real S3 POST policy key/byte rejection and immutable sealing.
HTTP tests use real PostgreSQL/auth and a storage fake; separate S3 integration tests
verify the transport. This is not a packaged end-to-end upload acceptance demo.

Operational events use eka.upload.events with fixed outcome labels; document IDs are
logged, never used as metric labels. Counters describe successful request returns,
including idempotent repeats; they are not counts of unique documents.
