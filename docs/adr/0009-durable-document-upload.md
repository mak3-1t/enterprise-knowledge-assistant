# ADR-009: Durable upload intents, quota reservations and immutable S3 completion

- Date: 2026-11-06
- Status: Locally verified (142 tests, 2026-11-06); merge pending
- Supersedes ADR-008's future-work statements for upload transport/API only.

Extend V4 with V5; never rewrite an applied migration. Keep storage_state internal,
add the approved upload lifecycle, reservation metadata and tombstones. Managed rows
use UploadRepository; the earlier repository cannot complete/fail managed intents.

Serialize admission on a tenant/user quota row and bind Idempotency-Key to a SHA-256
request fingerprint. Separate uploads still create separate IDs. Completion records
state and Outbox in a local transaction; external object writes require compensation
and maintenance. Quota release requires successful cleanup under the document lock.

Use signed POST with exact size/key/type conditions. Complete reads bounded staging
bytes, hashes them and conditionally writes the private original. Later staging writes
cannot overwrite original content. Hold the document lock during bounded external I/O
to prevent cancellation/purge from racing sealing; monitor lock latency and revisit
with durable leases at larger scale. Downloads use an authorized gateway to avoid
issuing capabilities that remain valid after logical deletion.

Shared tenant reads, 500 MiB/100 files per user and seven-day retained deletion were
explicitly approved. Restore enters UPLOADED and cannot reuse prior processing claims.
Only actual scan/parse/embed success may publish READY. The current slice does not
implement those pipeline gates or claim production readiness.

See [implementation](../document-upload.md) and [full contract](../document-upload-contract.md)
for configuration, tests, limitations and remaining work.

References:
- https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-HTTPPOSTConstructPolicy.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html
