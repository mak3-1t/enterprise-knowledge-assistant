# ADR-005: Restore ADMIN / EDITOR / VIEWER roles

- Status: Accepted policy (developer approval 2026-11-05); implementation pending PR/merge
- Date: 2026-11-05
- Scope: supersedes the temporary two-role assumption in ADR-004; its other decisions remain

## Context and decision

The original roadmap requires ADMIN, EDITOR and VIEWER. The first auth increment
used ADMIN/MEMBER. Restore the original three roles with forward migration V2,
leaving applied V1 unchanged. Convert MEMBER to VIEWER, preserving least privilege;
retain account/tenant IDs, credentials, active flags and timestamps. Never assign
EDITOR automatically during conversion.

Current identity is resolved from active DB records on each request, so existing
access JWTs use the migrated/current role without reissue. Member administration
and non-health actuator access remain ADMIN-only. EDITOR/VIEWER can read /api/me.

Document policy approved by the developer: all three read/download within tenant;
ADMIN manages all tenant documents; EDITOR uploads and deletes own uploads only;
VIEWER cannot write. This story does not implement document endpoints or ACLs.

## Consequences and alternatives

Existing development bootstrap configuration must replace MEMBER with VIEWER.
Stop older application instances before migration: old code cannot decode new roles.
The upgrade is not rolling-version compatible and has no automatic down migration.
Do not repair Flyway checksums or delete data volumes to upgrade.

Keeping MEMBER as a permanent fourth role would retain the roadmap mismatch and
require maintaining two meanings for a read-only account. Mapping MEMBER to EDITOR
would grant new write rights, so the approved migration uses VIEWER instead.

## Evidence

V1-to-V2 PostgreSQL test compares complete account/tenant rows, verifies current
constraints and no-op replay. HTTP tests reuse one token across role changes and
exercise role/tenant denial. On 2026-11-05 developer Maven verify passed 50 tests,
no failures/errors/skips; the packaged application demo passed 15 checks.
CI and merge still must pass before the implementation is delivered.
