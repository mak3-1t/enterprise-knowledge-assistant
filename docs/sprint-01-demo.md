# Sprint 1 auth-subset acceptance demo (S1-04)

This verifies delivered auth/account flows, not the full original Sprint 1 scope.
See [roadmap and outstanding requirements](roadmap.md).

## Scope and prerequisites

PowerShell 7 (`pwsh`), Java 21 and Docker Desktop with Linux containers.
Run from the repository root. The demo uses the packaged application, a temporary
PostgreSQL 17 container and ephemeral RSA keys. It does not connect to the Compose
database or require Ollama. Ports are allocated dynamically and bound to loopback.
If `pwsh` is not on PATH, invoke the installed PowerShell 7 executable by its absolute path. Windows PowerShell 5.1 is not supported by this script.

Allow several minutes for five application starts and the first image download.

## Build and run

```powershell
cd D:\My-project\Project-AKA\enterprise-knowledge-assistant
.\mvnw.cmd -B verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed; do not run the demo.' }
pwsh -NoProfile -File scripts/sprint-01-demo.ps1
if ($LASTEXITCODE -ne 0) { throw 'Acceptance demo failed.' }
```

For a local Maven mirror, add `-s <local-settings.xml>` to Maven. Do not commit
machine-specific settings. Maven verify must report no failures, errors or skips.
Do not run an old JAR after changing source.

## What the script does

1. Checks Docker, generates a random database/account password in memory and creates
   fresh signing keys in a private temporary directory. Windows ACL inheritance is
   removed; only the current account receives access.
2. Starts a uniquely named disposable PostgreSQL container with no persistent volume.
   Flyway owns schema creation. The password is passed through a child-process
   environment, never a command-line argument or committed fixture.
3. Starts the application in explicit dev bootstrap mode four times to provision
   A ADMIN, A VIEWER, A EDITOR and B ADMIN. Both admins use the same synthetic email in separate
   tenants. Provisioning uses the existing application runner, not unscoped SQL inserts.
4. Calls POST /api/auth/login and holds access tokens in memory. Checks both ADMIN
   identities and the VIEWER/EDITOR tenants using GET /api/me.
5. Checks tenant-filtered lists, page size 1 and own member details in both directions.
   Requests to another tenant's ID must match a random missing ID's JSON 404.
6. Checks VIEWER and EDITOR list/detail 403, anonymous 401 and malformed-token 401.
7. Repeats A ADMIN provisioning and login. Confirms the same user ID and exactly three
   A accounts; Flyway history must contain exactly two successful versioned migrations (V1 and V2).
8. Stops its own JVM and removes only its exact random container and temporary keys
   in a finally block, including on failure. Existing dev containers/data remain intact.

Only named PASS results are printed, never tokens, passwords or account lists.
No application logs are echoed. A nonzero exit indicates failure. If the terminal
is forcibly terminated, find the container named `eka-s1-demo-<run-id>` in Docker
Desktop and remove that specific container; inspect the matching temporary directory
before removing it. Never use global Docker prune or delete the Compose volume.

## Coverage and limits

The live demo complements the 46 automated checks reported for #28. Expired,
wrong-key/issuer/audience tokens, role changes, forged tenant inputs, inactive
identities and concurrent requests are covered by automated tests with controlled
fixtures/clocks; the demo does not wait 15 minutes for a real token to expire.
It verifies account isolation only. Document ACLs, RLS, registration, refresh tokens,
password reset, SSO, production key management and rate limiting remain deferred.

Manual API requests and expected response shapes are in
[tenant-member-api.md](tenant-member-api.md); signing/bootstrap details are in
[local-auth.md](local-auth.md).

## Verification record — 2026-11-04

- #28: developer supplied Maven BUILD SUCCESS, including Spotless/Checkstyle, before
  merge. Existing report: 27 auth + 19 app tests, no skips.
- #28 changes are present on local main at `a5d1532`; developer supplied clean status
  and synchronization with origin/main.
- S1-04 live demo: **PASS, 13/13 checks**, run by the developer on 2026-11-04
  using the packaged application and disposable PostgreSQL. Supplied terminal output
  confirms both tenant directions, pagination, MEMBER 403, missing/invalid-token 401,
  repeat provisioning without duplicates and a single successful Flyway migration.
- The agent could parse/review the script but could not access the Docker named pipe.
  The live result above is the developer's execution, not an agent-run claim.
- PowerShell 7 was available through its absolute local executable path; `pwsh` was
  not on the developer terminal PATH. Java/Docker version output was not captured.
- S1-04 PR #30 merged; main CI run 41987353426 passed, including this demo.
  This completes the auth-subset delivery; original Sprint 1 scope remains incomplete.

See the [technical retrospective](sprint-notes/sprint-01-retro.md). No credentials,
live tokens, personal feedback or unmeasured performance claims are recorded here.


## Three-role verification — 2026-11-05

The developer ran the updated script after Maven verify BUILD SUCCESS: **15/15 PASS**.
It provisions ADMIN/EDITOR/VIEWER, verifies both non-admin denials and checks that
V1 and V2 each apply once across restarts. Maven Surefire reports total **50 tests**
(29 auth, 21 app), with zero failures/errors/skips. Build time: 1 minute 19 seconds.
The historical 13/13 result above belongs to the earlier ADMIN/MEMBER script.
This story's GitHub CI and merge are pending; upload and other original Sprint 1
requirements remain outside this auth demo.
