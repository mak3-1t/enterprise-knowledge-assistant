# ADR-004: Local authentication and per-request tenant identity

- Status: Accepted for the local MVP
- Date: 2026-11-04
- Issue: #26 (Sprint 1 S1-02)

> Role-policy update: [ADR-005](0005-three-role-rbac.md) restores the original
> ADMIN/EDITOR/VIEWER roles and defines MEMBER -> VIEWER migration. Other local
> authentication decisions below remain in effect.

> Registration update: [ADR-006](0006-tenant-registration.md) adds explicit opt-in
> tenant signup, separate from dev bootstrap. Refresh tokens remain a separate story.

> Session update: [ADR-007](0007-refresh-sessions.md) extends login with seven-day
> rotating refresh tokens and immediately revocable sid-bound access. Historical
> stateless-token/refresh-deferred notes below are superseded for that scope.

## Context

EKA needs a repeatable login path before tenant member APIs and document ingestion.
The auth module already owns tenants/accounts. This iteration has no enterprise
identity-provider account or frontend and assumes one account belongs to one tenant.

## Decision

Use email/password login scoped by tenant slug. Spring Security BCrypt (strength 10)
verifies passwords. POST /api/auth/login issues a signed RS256 JWT with a 15-minute
lifetime. Its subject and tenant_id identify the account; it carries no password,
email or authorization role. Responses containing tokens use Cache-Control: no-store.
Unknown tenant/email, incorrect password and inactive identities all yield generic
JSON 401. A dummy BCrypt verification reduces the obvious missing-account shortcut;
this is not a claim of constant-time HTTP behavior or brute-force protection.

Use Spring Security's OAuth2 Resource Server and Nimbus JWT implementation. Only
RS256 with the configured RSA public key is accepted. Validate issuer, audience,
expiry/not-before and canonical UUID identity claims. Require issued-at/expiry and
limit lifetime to 900 seconds. Use an injected UTC clock; timestamp tolerance is
zero in this local single-issuer setup. Distributed deployments must revisit clock
synchronization/tolerance instead of silently widening acceptance.

For each authenticated request, read the active tenant/account pair and current
role from PostgreSQL. Ignore any supplied role claim or client tenant header. Role
changes and account/tenant disabling/deletion take effect on the next request.
SecurityContextHolder supplies a request-bound principal, cleared by Spring Security;
future modules consume CurrentAccount / AuthenticatedAccount rather than JWTs or tables.

The API is stateless and accepts Bearer headers, without cookie authentication,
form login or HTTP Basic. CSRF protection is disabled for this Bearer-only contract;
adding browser cookies will require revisiting CSRF. Health GET endpoints are public;
other actuator endpoints require current tenant ADMIN, as a local MVP operational
policy. Reassess that policy before exposing management endpoints to real tenants.
Other routes require authentication. Failures are JSON 401/403, without login redirects.

## Keys and provisioning

Require external PKCS#8 private and X.509 public PEM resources, at least 2048-bit,
with matching modulus. Key locations, issuer and audience have no production defaults.
Never generate a signing key silently at startup. The explicit developer utility
creates a 3072-bit pair and refuses to overwrite files. Restrict Windows directory
ACLs or use a secret store; .gitignore prevents PEMs from being accidentally staged.
No private key is checked into Git or served as JWKS by this MVP.

A provisioner exists only when dev is active, neither prod nor production is active,
and eka.auth.bootstrap.enabled=true. Tenant slug, email, password and role must be
explicit. It hashes before insertion and does not update an existing account. This
is not a registration endpoint or password-reset mechanism. Production account
provisioning must be designed separately.

## Alternatives

- External OIDC/SSO: better fit for enterprise rollout, but introduces provider setup
  and lifecycle work beyond this sprint. Resource Server leaves that migration open.
- Stateful sessions: possible for a browser-first app; the current API demo uses
  Bearer tokens. Adding a frontend may change token-storage/session choices.
- Roles stored only in JWT: fewer database reads, but role/disable changes would be
  stale for the lifetime. Current database checks favor immediate enforcement.

## Consequences and deferred work

Each protected request requires a database lookup; a database outage fails closed
instead of accepting a stale principal. No refresh, public registration, password
reset, logout denylist or per-token revocation is implemented. Password changes do
not invalidate already-issued tokens; disabling the account does. Tokens expire
after 15 minutes. Key replacement invalidates tokens signed with the prior key;
overlapping key rotation/kid registries are deferred. Production use also requires
HTTPS, login rate limiting, access-log redaction and an identity lifecycle strategy.
These are follow-up requirements, not functionality claimed by this issue.

## Verification

Unit tests exercise key loading, claim validation with a controllable clock and
provisioner profile/opt-in gates. PostgreSQL-backed HTTP tests cover login, invalid
and modified tokens, issuer/audience/algorithm/key errors, active identity and role
changes, independent tenant principals, public health and protected actuator rules.
Test-only endpoints and ephemeral keys are not packaged into the application.

## Official references

- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
