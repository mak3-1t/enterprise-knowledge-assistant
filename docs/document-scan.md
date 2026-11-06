# File validation and malware scan

Verified locally 2026-11-06: BUILD SUCCESS, 142 tests, zero failures/errors/skips.
The preceding upload baseline passed 114 tests in the developer run. That result does
not validate this new V6/worker slice. See [upload implementation](document-upload.md).

## Durable lifecycle

V6 adds document_scan_jobs and an independent scan_dispatched_at Outbox cursor. A
transaction transfers UPLOAD_COMPLETED/RESTORED events into one job per document
generation. Deletion events cancel older work. published_at remains unchanged: this
is a local PostgreSQL-backed worker, not a claim that Kafka publishing is implemented.

Claim uses FOR UPDATE SKIP LOCKED, random lease tokens and a five-minute lease.
First claim changes UPLOADED -> SCANNING atomically with job ownership. Scanner or
inspection unavailability retries with 5/10/20-second backoff, at most four attempts
(initial + three retries). Expired leases can be reclaimed after process crash; a
fourth crashed attempt becomes DEAD/FAILED instead of remaining stuck indefinitely.
DEAD is a durable local dead-letter state, not a Kafka DLT. No automatic replay endpoint
is exposed. Preserve failure records for diagnosis and cleanup.

Only the current lease and document version may finish. Delete/restore invalidates
old results; a stale worker cannot resurrect a document or publish a verified event.
A clean result commits PROCESSING plus CONTENT_VERIFIED Outbox event and job success
together. It never sets READY. Parsing/chunking/embedding are the next delivery.

## Gates and limits

1. Download a bounded private-original snapshot, deadline 60 seconds. Recheck exact
   bytes and sealed SHA-256 before sending anything to scanners. Changed bytes reject.
2. Stream to ClamAV using zINSTREAM, bounded chunks, total <=20 MiB. A watchdog closes
   the socket after 30 seconds, including blocked writes. Only a complete terminated
   `stream: OK` is clean. Truncation, malformed/error reply and unavailability retry.
3. Inspect content in a separate Java process, heap 256 MiB, deadline <=60 seconds.
   Child environment drops app credentials; output is discarded and verdicts use
   numeric exit codes. The packaged Boot JAR launcher is tested separately.

- PDF: strict initial signature, PDFBox 3.0.8 parsing, at least one page, encrypted PDF
  rejected, active actions/JavaScript/embedded attachment structures rejected.
- DOCX: ZIP signature, required content types/relationships/main document, secure XML
  parsing with no DTD/entities/external fetch. Reject macro parts and macro-enabled
  types, external relationships, traversal, duplicate normalized ZIP names and corrupt
  CRC/size. Bounds: 2,048 entries, 16 MiB/entry, 64 MiB total, compression ratio limit
  100:1 plus 1 MiB allowance, XML depth 128 and one million events.
- TXT/MD: strict UTF-8, reject binary signatures and unsupported control characters.
  Markdown HTML remains inert source text; no preview rendering is added. Future
  rendered previews must sanitize HTML. Source bytes are not silently rewritten.

Policy is intentionally restrictive: DOCX external hyperlinks/relationships and
active PDFs may be rejected even if a desktop editor opens them. Scanned image-only
PDFs can pass safety validation; text extraction/OCR behavior is a later parser concern.

Permanent failures show stable errorCode in metadata (TYPE_MISMATCH, CORRUPTED_FILE,
PASSWORD_PROTECTED, MACRO_DETECTED, ACTIVE_CONTENT_UNSUPPORTED, MALWARE_DETECTED,
SCAN_LIMIT_EXCEEDED). Transient scanner/inspection failures never imply clean. Rejected
and exhausted work uses existing cleanup/quota-release logic. No raw signature names,
document text, parser stack trace or temporary paths are exposed in HTTP responses.

## Local setup and verification

Both EKA_UPLOAD_ENABLED=true and EKA_UPLOAD_SCAN_ENABLED=true are needed to run scanning
in the application. Defaults remain false. ClamAV defaults to localhost:3310; override
EKA_CLAMAV_HOST/EKA_CLAMAV_PORT when needed. Start it and wait for healthy before enabling
the worker, so initial signature loading does not consume the short retry budget.

The malware Compose profile uses official clamav/clamav:1.4 (tracks LTS patch releases),
localhost-only TCP, persistent signature DB, 4 GiB memory/one CPU and healthcheck. It
enables AlertExceedsMax/AlertEncrypted so incomplete/encrypted scans are not reported
as normal clean content. Stream cap is 20 MiB, embedded file cap 64 MiB, aggregate scan
cap 128 MiB. Production requires reviewed image digest and ongoing signature freshness
monitoring; container health alone does not establish up-to-date signatures.

Run from the repository with Docker Desktop running:

    & "C:\Users\Thang\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe" -NoProfile -File .\scripts\verify-document-scan.ps1

The script starts only the ClamAV service, waits up to ten minutes, enables the opt-in
live test for that run, runs offline Maven verify and restores prior process env.
It leaves the development scanner running. Log: target/document-scan-verify.log.
Full developer run finished 2026-11-06 16:40:25 +07:00 in 2m30s: 142 tests passed, including the live smoke.
Without EKA_CLAMAV_TESTS=true, ClamAvLiveTest is explicitly skipped; report that honestly.
The live test sends clean bytes and the standard antivirus test signature entirely in
memory, without creating a signature file on the host. No actual malware is used.

Agent evidence: selected unit regression, compile all modules/test sources, formatting,
Checkstyle, Compose config parse, and child launcher smoke. PostgreSQL tests cover
single ownership, idempotent dispatch/handoff, crash recovery, stale lease rejection,
delete/restore generation fencing, retry exhaustion, malware rejection and transaction
rollback. HTTP tests combine real DB/auth with fake storage/scanner; real ClamAV smoke
and S3 transport tests are separate, not a packaged end-to-end pipeline claim.

## Remaining isolation and product work

The child JVM provides heap/wall-clock isolation, not a complete OS sandbox: filesystem,
network and CPU restrictions require a separately constrained runtime/container. No
full sandbox or production malware-proof guarantee is made. Application and child
temporary files are cleaned on handled paths; host/process crashes may leave temporary
files needing operational retention cleanup. Parsing/chunk/embed/generation publication,
Kafka publish/consume/DLT, stuck-job alerts, full daily storage reconciliation, resumable
upload and frontend progress remain pending. Download/search/chat stay blocked until
the actual pipeline publishes READY.

## References

- https://docs.clamav.net/manual/Usage/ClamdProtocol.html
- https://github.com/Cisco-Talos/clamav-documentation/blob/main/src/manual/Installing/Docker.md
- https://github.com/Cisco-Talos/clamav-docker/blob/main/clamav/1.4/alpine/scripts/docker-entrypoint.sh
- https://pdfbox.apache.org/3.0/getting-started.html
- https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html

## Verified delivery checkpoint

The developer ran scripts/verify-document-scan.ps1. target/document-scan-verify.log
contains BUILD SUCCESS. Surefire XML totals: auth 41, ingestion 57, app 44; all 142
passed, no failures/errors/skips. ClamAvLiveTest ran (not skipped), PostgreSQL/S3
integration and HTTP suites passed, and Spotless/Checkstyle passed. This is local
verification, not CI or a packaged end-to-end product acceptance claim. Merge and
the explicitly listed remaining pipeline/isolation work are still pending.
