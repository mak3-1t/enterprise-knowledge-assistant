# Sprint 1 planning — proposal

> Reconciled 2026-11-05: this document is the historical auth-subset plan, not the
> full original Sprint 1 scope. Its four implementation stories are delivered.
> Original Sprint 1 also requires registration, refresh, three-role RBAC, upload
> and OpenAPI; see [roadmap](../roadmap.md) and [completion backlog](sprint-01-completion.md).

Prepared: 2026-11-02. Status: proposed scope for developer review.
Sprint dates and capacity have not been agreed. Estimates are relative sizing,
not a delivery-time commitment. No implementation has started under this plan.

## Sprint goal

Demonstrate an authenticated, tenant-scoped backend: a user signs in, sees their
identity, and can access only the tenant data allowed by their role. Automated
negative tests must show that another tenant's users cannot be read.

## Starting point

Sprint 0 supplied the modular Spring Boot 4.1.2 / Java 21 skeleton, PostgreSQL
infrastructure, CI, quality gates and AI framework decision. The auth module
currently has no business implementation. No new AI work is required for this goal.

## Proposed scope and order

The IDs below are planning references, not GitHub issue numbers. Map them to real
issue numbers after creating the stories from [the backlog](sprint-01-backlog.md).

| Order | Reference | Story | Estimate | Depends on |
|---|---|---|---|---|
| 1 | S1-01 | PostgreSQL migrations, tenant/user schema and integration-test foundation | 3 | Sprint 0 |
| 2 | S1-02 | Local sign-in and validated short-lived JWT authentication | 5 | S1-01 |
| 3 | S1-03 | Tenant-scoped identity and member APIs with role checks | 3 | S1-02 |
| 4 | S1-04 | Repeatable two-tenant demo and Sprint 1 verification guide | 2 | S1-03 |

Proposed total: 13 points. This is not an established velocity. If capacity is too
small, re-scope the goal before committing; do not drop the isolation tests to fit.
Every implementation story includes its own tests and documentation updates.

## Working assumptions to review

- Backend/API only, exercised from PowerShell; no frontend this sprint.
- Local email/password sign-in with a tenant slug; external SSO/OIDC is deferred.
- One account belongs to one tenant in the MVP. Multi-tenant membership is deferred.
- Roles are ADMIN and MEMBER within a tenant. ADMIN is not a platform-wide role.
- Credentials use Spring Security PasswordEncoder with an adaptive hash (BCrypt).
- Access tokens expire after 15 minutes; no refresh token or public registration.
- JWT validation uses Spring Security, checks signature, allowed algorithm, issuer,
  audience, expiry and required identity claims. Do not implement a custom JWT parser.
- Tenant/user IDs are verified against active database records. Roles come from
  current server-side data so a role change is effective on the next request.
- Signed tokens identify the account; tenant identity never comes from a trusted
  client-supplied header or an arbitrary tenant ID in a request body.
- Signing keys and bootstrap credentials are supplied outside Git. No known default
  account, private signing key, token or password is committed or logged.
- Database structure is owned by Flyway migrations. If JPA is used, validate the
  schema; do not use automatic create/update as a second schema manager.

The local token issuer is an MVP implementation, not a claim of enterprise SSO
readiness. Record the auth decision and its tradeoffs in ADR-004 during S1-02.
Framework artifacts and APIs must be verified against official sources before coding.

## Data and module boundaries

The auth module owns tenants and accounts. Minimum data: tenant UUID/unique slug,
account UUID/tenant foreign key, normalized email unique within its tenant,
password hash, role, active status and creation timestamps. Foreign keys and
uniqueness must be enforced by PostgreSQL, not only by application checks.

Expose a small auth API for authenticated user ID, tenant ID and role. Future
modules must use this API rather than reading auth-owned tables directly.
No global mutable tenant context. Resolve identity per request; never retain a
previous user's tenant on a pooled thread. Explicit tenant-scoped queries are
the initial boundary; PostgreSQL row-level security is not delivered by this sprint.

## Proposed HTTP contract

| Endpoint | Access | Success | Denial |
|---|---|---|---|
| POST /api/auth/login | Anonymous; tenantSlug, email, password | 200 with access token, Bearer type and expiry | Generic 401 for invalid credentials or inactive/unknown identity; 400 for malformed input |
| GET /api/me | Authenticated active account | 200 with safe identity, tenant and role | 401 for missing, invalid or unusable authentication |
| GET /api/tenant/members | Tenant ADMIN | 200 with paginated safe account summaries for current tenant | 401 unauthenticated; 403 insufficient role |
| GET /api/tenant/members/{id} | Tenant ADMIN | 200 for an account in current tenant | 404 for absent or other-tenant account; 403 insufficient role |

No response contains password hashes or signing material. Health liveness remains
public; other actuator endpoints require explicit access rules. Protected endpoints
never redirect to an HTML login page. Authentication/authorization errors use JSON.

## Acceptance demo

1. Start PostgreSQL and the app with documented local configuration.
2. Provision tenant A ADMIN/MEMBER and tenant B ADMIN using the explicit dev setup.
3. Sign in as A ADMIN; /api/me identifies A and member listing contains only A users.
4. Request a B account ID with A's token; receive 404 with no B data.
5. Use A MEMBER to call member administration endpoints; receive 403.
6. Use no token, a modified token or an expired token; receive 401.
7. Repeat with B ADMIN and verify the reverse isolation direction.
8. Restart the app; migrations are not reapplied and no duplicate accounts appear.

## Verification and delivery

Run unit and PostgreSQL integration tests in Maven verify on CI, with isolated test
containers or an equivalent isolated PostgreSQL service. Tests must not use the
persistent dev database, an external identity service, Ollama or real credentials.
Keep existing Repository checks and Build and test check names. Test failures,
style violations and isolation failures all block merge.

Use one GitHub issue, branch and PR per story. Prepare code/tests/docs, let the
developer review and commit/push, then wait for green CI, squash merge and sync main
before starting the next story. Use the real issue number in Closes references.
Write the Sprint 1 retrospective at the actual end, based on measured outcomes.

## Deferred work

Document upload, storage adapters, parsing/chunking, embedding persistence, RAG,
streaming, agents, frontend, public account registration, password reset, refresh
and revocation flows, external SSO, platform tenant administration and Kubernetes.
These require later stories; do not silently expand Sprint 1 to include them.

## Official design references

- https://docs.spring.io/spring-boot/how-to/data-initialization.html
- https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
