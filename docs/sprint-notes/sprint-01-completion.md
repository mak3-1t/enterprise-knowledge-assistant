# Sprint 1 completion backlog — original scope

Updated: 2026-11-06. Supplements the delivered auth subset; see [roadmap](../roadmap.md).
References below are draft planning IDs, not GitHub issue numbers. Estimates for
new splits must be refined before Ready. Delivery updates below distinguish merged work.

## C1 — Restore original three-role RBAC

User story: as a tenant administrator, I want ADMIN/EDITOR/VIEWER permissions so
future document operations match the original roadmap and approved policy.

- Given an existing database, when the next Flyway migration runs, MEMBER becomes
  VIEWER without changing IDs, tenant, password or active flag; migration replay is safe.
- Given fresh or upgraded DB, only ADMIN/EDITOR/VIEWER can be persisted/provisioned;
  do not edit the already-applied V1 migration.
- Given an existing access token, current DB role applies on the next request.
  EDITOR/VIEWER receive 403 on member administration routes; all active roles can /me.
- Given dev fixtures/demo, bootstrap, DTO docs and tests use the three new roles.
  Tenant substitution, inactive identity and both isolation directions still fail safely.
- Document policy approved 2026-11-05: ADMIN all tenant documents; EDITOR upload and
  delete own uploads; VIEWER read/download; all three read within tenant initially.
  C1 establishes roles; document endpoint enforcement belongs to C4.

Depends on current merged auth. Estimate during refinement (must be <=8).

## C2 — Tenant registration and first ADMIN (EKA-1)

- Define request/response, slug/email normalization, uniqueness conflict/error policy
  and abuse/trust policy before Ready. Never trust client-supplied role/tenant UUID.
- Given valid registration, create tenant + first ADMIN atomically with adaptive
  password hashing and DB constraints; rollback leaves neither half-created identity.
- Concurrent duplicate registrations cannot create duplicate tenants/admins.
- Invalid inputs, duplicate policy, transaction rollback, safe errors/logs and
  cross-tenant isolation have PostgreSQL-backed HTTP tests.
- Production registration is separate from opt-in dev bootstrap; no default passwords.

Original estimate 3 points; refine actual contract before implementation.

## C3 — Refresh token lifecycle (remaining EKA-2)

- Record an ADR extending ADR-004: endpoint, expiry, rotation, hashed token storage,
  replay detection/revocation, logout behavior and concurrency policy before Ready.
- Valid refresh issues a short-lived access token with server-verified current identity.
  Expired/revoked/replayed refresh and inactive tenant/account fail generically.
- Raw refresh/access tokens, credentials and signing material are never logged/stored
  as plain database secrets; verify with tests and safe response caching rules.
- Controlled-clock, concurrent refresh, reuse/revocation and both tenant-direction
  tests run in verify. Describe password-change/key-rotation behavior explicitly.

Depends on auth; estimate after refinement, split if >8. No new lifetime assumed here.

## C4 — Tenant document upload, metadata and list (EKA-5)

- Confirm file formats/size, duplicate semantics, private bucket/key scheme, upload
  response and storage failure cleanup before Ready. Presigned URLs are undecided.
- ADMIN/EDITOR can upload; VIEWER cannot. Caller tenant/uploader/role cannot be forged.
  Metadata is ingestion-owned; other modules use only public APIs, not auth SQL.
- Successful upload means bytes + metadata persisted, with status UPLOADED; no parsing,
  embedding or Kafka requirement is silently added to Sprint 1.
- Lists/metadata reads are scoped before pagination. Safe DTOs omit internal storage
  credentials/keys. Both tenant directions and role denials have real storage/DB tests.
- Bound actual bytes and sanitize filename/media input; no file execution or inline
  rendering. Failed I/O/finalization must not expose partially stored documents.
- Download/delete, if included, follow approved role policy: EDITOR can delete only
  own uploads; ADMIN can delete any own-tenant document. Define deletion/recovery and
  HTTP behavior before adding those endpoints; they extend original upload/list scope.
- Split into schema, adapter and HTTP stories with real issue numbers and <=8 points
  as needed. Keep a durable handoff for Sprint 2 Outbox; no dual-write promise.

Original umbrella estimate 5; refine splits from the actual agreed contract.

## C5 — API documentation and Sprint 1 acceptance (EKA-6)

- Add compatible OpenAPI/Swagger for delivered endpoints, requests, responses and
  security requirements; determine UI access rules without widening protected data.
- Preserve public health and protected actuator rules with HTTP tests.
- Add document acceptance demo to auth demo; record actual fresh execution and CI.
- Original DoD gaps: implement a defined business-logic coverage >=70% gate,
  vulnerability scan with explicit handling policy, and full app Compose startup.
  Refine these as individual infrastructure stories rather than claiming them done.
- Merge with both required checks green, map/close issues and update original Sprint 1
  acceptance; create v0.1.0 only when its actual deliverable is complete.

C5 depends on completed original stories; EKA-6 could be moved only by explicit scope
change. Do not generate retrospective results before execution.


## Delivery update — 2026-11-06

- C1 roles: issue #32 / PR #33 merged.
- C2 registration: issue #34 / PR #35 merged, disabled by default.
- C3 refresh/logout: issue #36 / PR #37 merged; main CI passed, 80 tests/demo 25 documented.
- C4 policy approved: PDF/DOCX/TXT/MD, one file/request, <=20 MiB, duplicates create separate documents.
- C4 split 1: [metadata foundation](../document-metadata.md) prepared, full verification/PR pending.
- C4 next: private S3 adapter/recovery, then authorized upload/list API. No upload endpoint exists yet.
- C5 OpenAPI and remaining DoD gates remain pending. Original Sprint 1 is not closed.

## Expanded upload contract — approved 2026-11-06

See [upload contract](../document-upload-contract.md). Shared tenant reads remain; quota
is 500 MiB/100 files per tenant/user including reservations and retained deletions.
Soft delete retains original bytes seven days. Idempotent retries reuse the intent;
new uploads still create separate documents. Lifecycle/quota/outbox and recovery are
required next slices; scanner/parser/embedding gates are implemented with Sprint 2.
This is an approved specification, not evidence of completed upload functionality.

### Implementation update

[Upload/API/recovery slice](../document-upload.md) is prepared, pending Docker-backed
verification. V5 adds quota/idempotency/lifecycle/Outbox; HTTP and S3 adapter exist.
Scan/parse/embed gates, multipart/resume, full daily reconcile and UI remain incomplete.

### Content scan implementation — 2026-11-06

[V6/worker](../document-scan.md) adds content inspection, ClamAV, leases, bounded retries,
generation fencing and local dead-letter state. Integration verification is pending.
Clean files enter PROCESSING; parse/chunk/embed and READY publication remain pending.
The local Outbox cursor does not mark events published to Kafka.

### Verified checkpoint — 2026-11-06

The full upload/content-scan verification passed 142 tests (zero failures/errors/skips),
including live ClamAV, PostgreSQL/S3 and HTTP suites. BUILD SUCCESS at 16:40:25 +07:00.
See [verification evidence](../document-scan.md). Merge/CI and the remaining parse/chunk/embed/READY/isolation scope are pending.
