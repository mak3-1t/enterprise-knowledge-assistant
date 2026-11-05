# Auth roles and V2 upgrade

## Role contract

The original roadmap uses ADMIN, EDITOR and VIEWER. V2 removes the temporary MEMBER
role and maps existing MEMBER accounts to VIEWER. ADMIN accounts retain their role.

| Existing route | ADMIN | EDITOR | VIEWER |
|---|---|---|---|
| GET /api/me | own identity | own identity | own identity |
| GET /api/tenant/members and /{id} | own tenant | 403 | 403 |
| Non-health actuator routes | allowed | 403 | 403 |

Roles are loaded from active database records for each authenticated request; JWT
role claims and caller headers cannot grant authority. Role changes apply on the
next request using the same token. Tenant boundaries remain unchanged.

Approved document policy (not implemented by this story): all three read/download
within tenant, ADMIN manages all own-tenant documents, EDITOR uploads and deletes
only own uploads, VIEWER cannot write. Document endpoints require their own tests
and must not use this role migration as evidence that upload/ACL has shipped.

## Upgrade existing databases

Build with Maven verify, then start the new application normally. Flyway validates
unchanged V1 and applies V2 transactionally: drop the old role constraint, convert
MEMBER -> VIEWER, add the new role constraint. It changes no password, user/tenant
ID, active flag or timestamp. Repeated startup validates/applies nothing again.

Stop the older application version before upgrading; old code cannot decode the
new database roles. If several instances exist, stop all of them first. This local
project upgrade is not a rolling-version compatibility claim. Back up persistent
DB data through the usual local DB procedure before upgrading. Do not delete volumes,
edit V1, use Flyway repair or manually change migration history. V2 has no automatic
down migration; rollback requires a separate reviewed plan.

Change EKA_AUTH_BOOTSTRAP_ROLE=MEMBER to VIEWER before running the upgraded app in dev.
Otherwise bootstrap rejects configuration. Provisioning never changes existing
credentials/roles. No public role-management endpoint is added.

V2 conversion updates all existing MEMBER rows, including disabled accounts and
accounts under inactive tenants, without reactivating either. EDITOR is an explicit
new role and is never assigned automatically to former MEMBER accounts.

## Verification

```powershell
.\mvnw.cmd -B verify
pwsh -NoProfile -File scripts/sprint-01-demo.ps1
```

Requires Docker Engine for PostgreSQL tests and demo. Use an absolute PowerShell 7
executable path if pwsh is not on PATH. ThreeRoleMigrationTest starts at V1, inserts
legacy MEMBER and ADMIN records, upgrades to V2 and compares complete rows, repeats
migration, and verifies role constraints. Fresh-schema repository/app tests use V2.
HTTP tests reuse a token across all three role changes and exercise /me, member
list/detail, foreign IDs and actuator access. Bootstrap covers all current roles and
rejects legacy MEMBER before creating a tenant.

Developer verification on 2026-11-05: Maven verify BUILD SUCCESS (1 minute 19 seconds),
50 tests passed (29 auth, 21 app), zero failures/errors/skips. Live demo: 15/15 PASS,
including both non-admin denials and V1/V2 replay. CI and PR merge remain pending.
The old 13-check result is historical only.

## References

- [Flyway versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [Spring Security request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
