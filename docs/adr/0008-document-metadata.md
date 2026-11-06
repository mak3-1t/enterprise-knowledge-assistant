# ADR-008: Tenant-owned document metadata before storage/API integration

- Status: Locally verified (142 tests, 2026-11-06); merge pending
- Date: 2026-11-06
- Scope: first slice of original Sprint 1 EKA-5

## Decision

Create ingestion-owned metadata in V4 without changing auth migrations or reading
another module's tables. The future service takes tenant/uploader from auth public API.
All repository reads/transitions require tenant scope, with version-checked completion
or failure so competing writers cannot overwrite one another.

Developer approved PDF/DOCX/TXT/MD, one file/request, actual size <=20 MiB and separate
documents for repeated content. Use fresh server IDs and tenant/document-based private
object keys, with database format/key/size/hash/state constraints. No checksum deduplication.

Separate storage_state (UPLOADING/STORED/FAILED) from ingestion_status. Keep unfinished
or failed storage intents out of normal lists. Set ingestion status UPLOADED only on
completion; reserve PROCESSING/READY/FAILED for later pipeline implementation. A single
status mixing storage failure with processing failure would hide useful document status
or expose unfinished uploads, so distinct state axes are used.

## Consequences

Ingestion can test its migration/repository without auth tables. Tenant/uploader
validity must be verified at the service boundary; UUID fields alone do not establish
authorized ownership. Internal rows include object keys and must be mapped to safe
HTTP DTOs. No upload endpoint/byte transfer or claim of document ACL delivery follows
from this migration alone.

S3 adapter, file-content validation, bounded streaming, compensation/recovery,
authorized upload/list and their metrics follow in separate stories. Download/delete
extend the original upload/list scope and require their own ready contracts. Outbox,
parse/chunk/embed and processing transitions remain Sprint 2.

## Verification plan

Real PostgreSQL tests cover scope, visibility, constraints, duplicates and transition
races. App migration test preserves V3 auth/session data through V4. Preserve existing
auth/session test/demo gates, recording actual outcomes only after full Docker execution.

## References

- [Flyway versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- ADR-001 module/data ownership; ADR-003 S3 protocol boundary

## Follow-up

[ADR-009](0009-durable-document-upload.md) implements the subsequent lifecycle,
quota, S3/API and recovery slice. Its current verification state supersedes the
future-work statements here; full scan/processing gates remain pending.
