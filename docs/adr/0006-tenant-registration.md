# ADR-006: Explicit opt-in tenant registration

- Status: Accepted policy (developer approval 2026-11-05); implementation pending verification/merge
- Date: 2026-11-05
- Extends: ADR-004 local authentication and ADR-005 roles

## Context and decision

Original Sprint 1 includes tenant registration and a first ADMIN. Earlier local
auth supplied only dev bootstrap. Add POST /api/auth/register behind
EKA_AUTH_REGISTRATION_ENABLED, default false. A valid disabled request returns JSON
404 without writes; explicit true permits anonymous creation of a new tenant only.
No existing tenant can be joined/overwritten and no role is selected by the client.

Normalize slug/email, validate bounded input/password UTF-8 bytes, hash with existing
BCrypt and atomically commit tenant + ADMIN through a short repository transaction.
Unique tenant slug handles concurrent duplicates. Generic 409/503 errors omit database
and credential details. Record bounded success only after commit. Login remains
separate; refresh tokens are not introduced by registration.

## Consequences and alternatives

Self-registration is closed on ordinary deployments until explicitly enabled. Closing
it later leaves existing identities/login operational. This is not email/domain
verification, invitation-based onboarding, production abuse prevention or tenant quota.
The operator must intend self-service before enabling; later controls need separate stories.

Always-open registration would silently expand deployment access. Reusing dev bootstrap
would mix a local provisioning tool with an external API. Both alternatives are rejected.
No schema changes, public role-management API or implicit token generation is needed.

## Verification

Unit tests cover the gate and input contract. PostgreSQL HTTP tests cover a failed
ADMIN insert rolling back the tenant, concurrent duplicates, role/ID forgery and
new-tenant isolation. The packaged-app demo explicitly opens/closes the switch.
Developer verification on 2026-11-05: Maven verify BUILD SUCCESS, 65 tests with zero
failures/errors/skips, and packaged-app demo 21/21 PASS. GitHub CI and merge remain
pending.

## References

- [Spring declarative transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
