# Secure document upload contract

Status: approved requirements, implementation in progress (2026-11-06).
The metadata foundation is an internal persistence slice, not this completed flow.

## Approved policy

- One PDF, DOCX, TXT or MD per request; 1..20,971,520 bytes. Reject legacy DOC/DOCM.
- ADMIN and EDITOR upload. All active roles read documents in their own tenant.
  ADMIN deletes/restores any tenant document; EDITOR only their own. VIEWER cannot mutate.
- Quota per tenant/user: 524,288,000 bytes and 100 files. Reserve pending uploads;
  retained deleted files count until physical cleanup is confirmed.
- A new upload creates a separate document, even for identical bytes. Retrying the
  same Idempotency-Key and request fingerprint returns the same intent; conflicting
  payload returns 409. Scope keys to authenticated tenant and uploader.
- Soft deletion denies access immediately. Retain original bytes seven days for
  restoration; never restore rejected/malicious content or republish stale work.
- No project/workspace entity is introduced. Cross-user access prohibition in the
  source example means cross-tenant denial under this approved shared-tenant policy.

## Lifecycle and atomic boundaries

PENDING -> UPLOADED -> SCANNING -> PROCESSING -> READY.
PENDING may expire; validation/scan may REJECT; processing may FAIL. Any live state
may become DELETED. Only READY content participates in chat/search. A status list
may show processing/errors without granting download or model access to quarantine.

Use tenant/id/version/state conditional updates. Reserve quota and create intent in
one PostgreSQL transaction with a locked quota owner row; concurrent requests cannot
oversubscribe. Complete binds an immutable object version, measured size and SHA-256,
then writes UPLOADED and an Outbox event in one DB transaction. Repeated complete
returns the recorded result and creates no second event. Expired/deleted intents
cannot complete, even before scheduled cleanup runs.

Storage and PostgreSQL have no shared transaction. Persist retryable cleanup work,
compensate external writes, and reconcile after crashes. Never report a DB rollback
as proof that S3 bytes were removed. Release quota only after confirmed cleanup.

## Upload transport and integrity

Intent expires after one hour; upload authorization lasts 5-15 minutes. Use random
server-controlled staging keys, never a caller path. Enforce actual byte bounds at
storage or the bounded upload gateway. A signed Content-Type alone proves no format.
Presigned POST policies support content-length-range; do not assume a PUT URL has the
same policy. Verify the chosen local S3 implementation with real integration tests.

Presigned URLs are reusable until expiry: completion must capture an immutable
version or private snapshot that later PUTs cannot replace. Cancellation tombstones
remain authoritative; late uploads are cleanup candidates. Multipart/resumable uploads
above 10 MiB require bounded parts, completion validation and abort on cancel/expiry.
Client progress/cancel/backoff belongs to the frontend delivery; backend contracts
must support it. ETag is not a general SHA-256 checksum. Verify server checksum or
stream the immutable bytes through a bounded SHA-256 calculation.

## Quarantine and processing

Validate signatures and structure, not just extension. TXT/MD require valid UTF-8;
DOCX requires bounded ZIP entries, expansion bytes/ratio and expected package structure.
Reject vbaProject.bin, corrupt archives and password-protected PDFs. Detect PDF active
content and prevent inline execution. Preserve source bytes; sanitize rendered previews.
Scan before publication; unavailable scanner never implies clean. Parser sandbox has
CPU/RAM/network restrictions and a 60-second deadline. Empty extraction reports
EXTRACT_EMPTY (scanned PDFs need a separately supported OCR path).

Normalize extracted text, chunk 500-1000 tokens with overlap and heading awareness,
embed with document/tenant/generation metadata. Retry three times with exponential
backoff, then DLQ/FAILED. Write a new generation idempotently and publish atomically;
do not delete the last published chunks before a replacement succeeds. Every worker
checks deletion/generation before publishing so stale work cannot resurrect a file.
Document text is untrusted model input and cannot override system/tool instructions.

## Recovery, deletion and serving

Hourly expiry cleanup; daily DB/storage reconciliation; staging lifecycle after 24h
and incomplete multipart abort after 1-2 days. Durable processing objects must not
remain under an unconditional staging expiry rule. Failed/rejected content and partial
derivatives are deleted by retryable cleanup while safe reason metadata remains.

Soft delete hides immediately, tombstones work, and removes searchable derivatives.
Seven-day restore revalidates eligibility and rebuilds derivatives if necessary;
physical purge is retryable. Existing citations return an unavailable-source result.
Immediate read revocation plus retained bytes requires an authorized download gateway;
a previously issued direct signed GET remains valid until expiry. Serve attachments
with nosniff at that gateway; do not assume arbitrary S3 response headers are supported.

## Errors and evidence

Stable codes: FILE_TOO_LARGE, UNSUPPORTED_TYPE, TYPE_MISMATCH, PASSWORD_PROTECTED,
MALWARE_DETECTED, CORRUPTED_FILE, EXTRACT_EMPTY, QUOTA_EXCEEDED, PROCESSING_TIMEOUT.
Never expose parser stack traces, credentials, signed URLs or document text in logs.
Use file IDs in logs/traces; low-cardinality metrics for status/type/latency/queue and
alerts for stuck work, cleanup failures and DLQ growth.

Required integration evidence: fake extensions, zero/oversize files, unsafe/Unicode
names, encrypted/scanned PDFs, corrupt/macro/bomb DOCX, MD scripts; same-key retries,
conflicting keys, complete twice, late PUT after complete/cancel, concurrent quotas,
DB/storage failures, worker crash/redelivery, stale publish after delete, restore,
retention purge, and both tenant directions plus every role boundary.

## Delivery sequence

1. Extend metadata with lifecycle, expiry, idempotency, quota reservations, tombstones
   and transactional Outbox; upgrade from V4 without rewriting applied migrations.
2. Private storage adapter, immutable completion, bounded transfer, compensation,
   cancel/expiry/reconcile and authenticated upload/list/download/delete contracts.
3. Validation and scan gates, then isolated parse/chunk/embed workers and DLQ (Sprint 2).
4. Restore/purge acceptance, OpenAPI and full failure-injection demo. Frontend progress
   and live status join the planned UI story. Never label an unscanned file READY.

The new contract expands the original upload story. Sprint 1 may prove durable upload
and UPLOADED status; READY remains blocked until the actual scan/processing pipeline
passes. Existing V4 storage states are internal only and do not implement this lifecycle.

## References

- https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html
- https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-HTTPPOSTConstructPolicy.html
- https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html
