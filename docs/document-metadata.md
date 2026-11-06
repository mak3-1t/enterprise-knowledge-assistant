# Document metadata foundation

## Expanded upload requirements

See [secure upload contract](document-upload-contract.md) for the approved lifecycle,
quota, idempotency, quarantine and seven-day deletion retention. The current V4 slice
is internal persistence only; STORED never authorizes chat/search or bypasses scanning.
The next migration must extend this foundation without rewriting an applied V4.

## Scope and approved policy

This is the first slice of original Sprint 1 EKA-5: ingestion-owned metadata and
tenant-scoped persistence. It does not expose upload/list/download/delete endpoints
or write any object-store bytes. S3 adapter and authorized HTTP service are the next
stories; schema tests are not evidence that document upload is implemented.

Developer decisions on 2026-11-06:
- One file per request, PDF/DOCX/TXT/MD, maximum 20 MiB (20,971,520 actual bytes).
- Each repeated upload is a separate document with a fresh ID/object key, including
  identical content in the same tenant. SHA-256 is not a uniqueness constraint.
- Approved roles remain ADMIN manage all own-tenant documents, EDITOR upload/delete
  own uploads, VIEWER read/download; all three initially read within tenant.

MIME values are canonical metadata derived from DocumentFormat, not trusted caller
headers. Actual file signature/encoding/size validation and role enforcement belong
to the forthcoming upload service. A row with format PDF does not prove PDF bytes.

## Ownership and migration

V4 creates ingestion.documents. V1/V2/V3 remain unchanged. Ingestion owns this schema
and never queries auth tables or adds cross-module FKs. tenant_id/uploaded_by are
UUIDs from verified CurrentAccount at the future service boundary. Repositories
require explicit tenant scope but cannot authenticate a bare UUID by themselves.

The application sees all four migrations. Auth-only module tests still see V1..V3;
ingestion-only tests see V4 independently. The application upgrade test starts at V3,
preserves complete account/session/refresh records, then applies V4 once. Never
renumber V4, edit applied migrations, delete dev volumes or repair migration history.

## Data and visibility

Records hold document/tenant/uploader UUIDs, display filename, format, private key,
measured byte size/SHA-256, timestamps, storage state, ingestion status and version.
No document contents or credentials are stored. The internal DocumentRecord contains
object_key and must never be serialized directly as an HTTP DTO; its toString redacts
metadata. Later APIs must expose safe response DTOs and verify roles before access.

Object keys are server-derived: tenants/<tenant-uuid>/documents/<document-uuid>/original.
They contain no filename and the database checks their ownership against the row IDs.
Storage adapter will use a configured private bucket; bucket/endpoint credentials do
not belong in metadata DTOs or logs. No public/presigned download URL is added here.

| Storage state | Size/hash/uploaded timestamp | Ingestion status | Visible for reads/lists |
|---|---|---|---|
| UPLOADING | null | null | no |
| STORED | required and valid | initially UPLOADED | yes, within tenant |
| FAILED | null | null | no |

Ingestion status reserves UPLOADED/PROCESSING/READY/FAILED for Sprint 2. This repository
only writes UPLOADED when storage completion is recorded. STORED is a byte-storage
state, never a claim that parsing/embedding succeeded. Future ingestion failure can
remain visible as metadata because visibility filters storage_state, not pipeline state.

## Operations and race behavior

- createUpload allocates an intent using caller-independent server document ID,
  verified tenant/uploader and validated basename. It returns the internal key for
  the adapter. The caller must not mark completion before bytes are durably stored.
- completeUpload requires tenant/id/expected version plus actual size and SHA-256.
  It changes UPLOADING -> STORED/UPLOADED in one conditional SQL update.
- failUpload changes UPLOADING -> FAILED with the same tenant/version guard.
- Completion and failure race: one update wins, version increments once, the other
  returns false. Wrong version, foreign and missing IDs produce the same false result.
- findUpload is ingestion-internal intent access for completion/cleanup; findStored
  and listStored hide pending/failed uploads. No unscoped ID lookup/list exists.
- List filtering precedes ORDER BY id/LIMIT/OFFSET. Limit range is 1..100, offset is
  non-negative. HTTP page/default/overflow binding belongs to the later API story.

A false completion result must never be treated as a successful public upload. The
storage story must handle compensation and restart/crash cleanup around DB/S3 I/O;
these repository transitions alone are not distributed transaction recovery.

## Verification — full Docker run pending

Repository tests cover intent visibility, tenant isolation in both directions,
duplicate content, storage invariants and limits, stable filtered pagination, terminal
failure/stale versions and concurrent complete/fail. Unit tests verify invalid input
is rejected before SQL. PostgreSQL tests and app V3->V4 migration test use disposable
containers, not the dev DB or cloud credentials.

Run Maven verify with Docker, then the existing auth/registration/session demo. Expected
suite: 91 tests; demo remains 25 checks with four applied migrations. The demo proves
packaged startup/auth regression only; there is no document upload demo yet.

New runtime HTTP/storage metrics belong to the service/adapter story. Current evidence
is Flyway startup logging and persistence tests, not an implemented upload flow.

## Implementation update

V5 and the S3/API slice now extend V4. See [current implementation](document-upload.md).
Earlier V4-only verification counts and future-work notes above describe the first
slice; current full-suite expectation is 114 tests and five migrations, pending Docker.

### Verified checkpoint — 2026-11-06

The full upload/content-scan verification passed 142 tests (zero failures/errors/skips),
including live ClamAV, PostgreSQL/S3 and HTTP suites. BUILD SUCCESS at 16:40:25 +07:00.
See [verification evidence](document-scan.md). Merge/CI and the remaining parse/chunk/embed/READY/isolation scope are pending.
