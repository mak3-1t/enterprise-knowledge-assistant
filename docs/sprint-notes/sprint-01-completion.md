# Sprint 1 completion backlog — original scope

Updated: 2026-11-05. Supplements the delivered auth subset; see [roadmap](../roadmap.md).
References below are draft planning IDs, not GitHub issue numbers. Estimates for
new splits must be refined before Ready. No implementation is marked complete here.

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
