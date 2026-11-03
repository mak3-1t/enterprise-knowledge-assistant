# Sprint 1 proposed issue backlog

Status: drafts for review, not GitHub issues. Use real issue numbers only after
creation. Estimates and scope are proposed; see [the plan](sprint-01-planning.md).
Each story must satisfy CONTRIBUTING.md. Do not create retrospective results early.

## S1-01 — build(auth): add tenant schema and PostgreSQL integration tests

### User story
As a developer, I want a versioned tenant/account schema and reproducible database
tests so authentication and isolation can be built on a verified persistence layer.

### Acceptance criteria
- Flyway migrates a fresh isolated PostgreSQL database and succeeds on a second
  startup without destructive schema changes or duplicate migration execution.
- Auth owns tenant and account tables with UUIDs, required foreign key, active
  status, role, password hash and timestamps. No plaintext password column exists.
- Tenant slug is unique. Normalized account email is unique within one tenant;
  the same email in another tenant is allowed. Invalid references/roles are rejected.
- Repository operations that access accounts use tenant scope, except the narrowly
  defined credential lookup by tenant slug plus normalized email.
- Integration tests exercise constraints and both tenant directions against real
  PostgreSQL. Tests run during Maven verify locally and in CI, without the dev DB.
- Connection configuration comes from environment/profile settings; examples use
  placeholders. No schema auto-update or dev credential seeding runs in production.
- Existing CI, style checks and app startup tests remain green; local setup is documented.

### Estimate and dependency
3 points; depends on merged Sprint 0. Owns auth persistence and minimal app wiring.

## S1-02 — feat(auth): add local login and JWT authentication

### User story
As an active tenant account, I want to sign in and obtain a short-lived access
token so I can authenticate later API requests.

### Acceptance criteria
- POST /api/auth/login accepts tenantSlug/email/password, validates input and
  verifies an adaptive password hash using Spring Security PasswordEncoder.
- Successful login returns a signed 15-minute Bearer access token and expiry.
  Invalid credentials, unknown tenant and inactive identity return generic 401.
- No public registration, refresh flow or default production account is introduced.
- Protected /api/** routes require validated JWTs; malformed, expired, wrong-key,
  wrong-algorithm, wrong-issuer or wrong-audience tokens return JSON 401.
- Require well-formed subject and tenant claims, validate their relationship to an
  active database account, and resolve current role from server-side records.
- Authentication failure never discloses which tenant/email exists. Logs and errors
  never contain passwords, token bodies or signing secrets; record safe auth outcomes.
- Key loading and local account provisioning are explicit and documented. Missing
  required signing configuration fails clearly; dev provisioning is opt-in and
  never enabled by the production profile.
- Tests cover correct/incorrect password, inactive identity, input validation,
  token validation failures and role/account changes. Time-dependent tests use a
  controllable clock. Explicitly test public health and protected actuator access.
- ADR-004 records local issuer vs external IdP, one-account/one-tenant assumption,
  token lifetime, key management, expiry behavior and deferred lifecycle features.

### Estimate and dependency
5 points; depends on S1-01. Keep framework security types within auth adapters.

## S1-03 — feat(auth): enforce tenant-scoped identity and member access

### User story
As a tenant user, I want my identity and member access to respect my tenant and
role so that another organization's accounts cannot be disclosed.

### Acceptance criteria
- GET /api/me returns safe current identity/tenant/role fields for an active account.
- GET /api/tenant/members returns only current-tenant summaries to ADMIN, with
  bounded pagination (default 20, maximum 100) and deterministic ordering.
- GET /api/tenant/members/{id} returns a current-tenant account to ADMIN; absent and
  cross-tenant IDs both return 404. MEMBER receives 403 for both member endpoints.
- Tenant ADMIN cannot act as a platform admin. Caller headers/body/query parameters
  cannot override the authenticated tenant or assign a role.
- DTOs never expose hashes or internal credentials. The service/repository query
  includes tenant scope before reading or paginating data.
- A small auth-owned public API exposes verified current user/tenant/role for future
  modules, without exporting persistence entities or permitting cross-module SQL.
- HTTP and PostgreSQL tests use A ADMIN, A MEMBER and B ADMIN, verify both directions
  of tenant isolation, guessed foreign IDs, role denials and forged tenant headers.
- Interleaved requests do not leak a previous request's tenant. Inactive/deleted
  identities are denied. All tests execute under verify with no external services
  beyond the isolated PostgreSQL test dependency.
- Document endpoints, status codes, example requests and safe authorization logging.

### Estimate and dependency
3 points; depends on S1-02. This proves account-data isolation, not future document ACLs.

## S1-04 — docs(auth): add repeatable Sprint 1 acceptance demo

### User story
As the developer/reviewer, I want a reproducible two-tenant demo so I can verify
the sprint goal on a fresh local setup without relying on hidden manual steps.

### Acceptance criteria
- A local guide documents PostgreSQL startup, migrations, signing-key setup,
  explicit dev-only account provisioning, app startup and cleanup of test fixtures.
- Provisioning is repeatable and does not overwrite existing credentials or create
  duplicate tenants/accounts. Passwords are supplied locally, never committed.
- Copyable PowerShell commands demonstrate login, /api/me, paginated member reads,
  MEMBER 403, cross-tenant 404 and invalid-token 401 in both tenant directions.
- Commands do not print signing secrets or encourage pasting live access tokens
  into logs/issues. Failures include expected HTTP status for comparison.
- Demo is performed and its actual results recorded when implemented; do not mark
  it passing based only on this plan. Required CI and tests for prior stories pass.
- README links to the guide. Record remaining limitations, then write the sprint
  retrospective from actual outcomes without inventing personal feedback or metrics.

### Estimate and dependency
2 points; depends on S1-03. Keep runtime behavior changes in the implementation stories.
