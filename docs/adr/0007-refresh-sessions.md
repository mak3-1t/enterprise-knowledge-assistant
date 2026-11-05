# ADR-007: Rotating refresh secrets and immediately revocable sessions

- Status: Accepted policy (developer approval 2026-11-05); implementation pending verification/merge
- Date: 2026-11-05
- Extends: ADR-004 token lifecycle; other auth/registration/role decisions remain

## Decision

Use seven-day absolute DB sessions with single-use, 256-bit opaque refresh tokens,
stored only as SHA-256 digests. Access remains RS256 and <=15 minutes, capped by
session expiry. Login creates a new independent session; refresh cannot slide its
expiry. No refresh cookies or external identity service is introduced.

Require canonical sid in each JWT and validate active DB session against the verified
tenant/user on every request, together with existing current identity/role checks.
Logout revokes that authenticated session immediately, including its access JWTs.
Legacy JWTs without sid are rejected after deployment and require reauthentication.

Serialize rotation/replay on the session row. Commit consumed-token replay revocation
before raising a generic 401 outside the transaction. Sign and persist issuance in
one transaction; signing/insert failures roll back, preserving the previous usable
refresh token. Retain consumed digests until the family expires for reuse detection.

## Tradeoffs and precise scope

Two concurrent refreshes are treated as rotation followed by replay; a winning pair
can already be invalid when delivered. Clients serialize refresh and reauthenticate
on ambiguous lost responses. A reuse grace period/returning stored successor would
require additional state and weaker replay semantics, so neither is included.

DB revocation adds authorization state/reads but fits the existing per-request DB
identity design. It does not create a servlet session, cancel in-flight requests,
provide logout-all/password reset or implement a complete OAuth authorization server.
Other sessions are isolated. Password changes alone do not revoke sessions; future
credential-management features must decide that explicitly. Signing-key changes
invalidate old JWTs but do not revoke opaque refresh sessions.

An absolute seven-day expiry was selected over sliding sessions. Immediate session
revocation was selected over allowing logged-out access JWTs to run for 15 minutes.
Stop old instances before V3; no mixed-version rollout/automatic down migration.

## Verification plan

Unit tests: entropy/canonical encoding/digests, JWT sid/lifetime/claims/redaction.
Real PostgreSQL HTTP tests: rotation/replay revocation commit, exact expiry, current
roles/identity, independent sessions, concurrent refresh/logout, forged sid and signer
rollback. Test V2 upgrade/FK ownership and preserve existing auth/registration tests.
Packaged demo explicitly refreshes, replays and logs out with live database state.
Developer verification on 2026-11-05: 80 tests passed (41 auth, 39 app), with no
failures/errors/skips; packaged-app demo passed 25/25. Agent compile, 26 selected
unit tests and style gates passed. GitHub CI and merge remain pending.

## References

- [RFC 9700 refresh-token protection](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14)
- [Spring transactional behavior](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
