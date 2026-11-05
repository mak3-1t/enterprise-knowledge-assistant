# Refresh tokens and revocable sessions

## Approved contract

Access JWTs last at most 15 minutes. Each successful login creates an independent
session with an absolute seven-day expiry (604800 seconds). Refresh rotates its
single-use secret without extending that deadline. The developer approved seven
days and immediate per-session logout on 2026-11-05; see ADR-007.

Login and refresh responses contain accessToken, tokenType=Bearer, expiresIn,
refreshToken and refreshExpiresIn (remaining seconds). Near the seven-day boundary,
access expiry is capped by the session deadline and can be shorter than 900 seconds.
No cookies/browser sessions are introduced. Keep both secrets out of logs, URLs,
issues, telemetry and source control; use HTTPS for non-local transport.

## HTTP API

| Request | Authentication | Success |
|---|---|---|
| POST /api/auth/login | Existing tenant/email/password | 200 access + refresh pair |
| POST /api/auth/refresh | JSON refreshToken, no Authorization header needed | 200 replacement pair |
| POST /api/auth/logout | Current valid Bearer access JWT | 204, revoke only that authenticated session |

Refresh body:

```json
{"refreshToken":"<opaque-secret-from-login>"}
```

Unknown, used, expired or revoked refresh tokens and inactive identities return
JSON 401 {"error":"unauthorized"}. Malformed JSON/missing/wrong-shaped inputs return
400 {"error":"invalid_request"}; noncanonical base64 secrets also fail safely.
DB/signing unavailability returns generic 503. Responses use no-store; token pairs
also use Pragma: no-cache. Never send an expired Bearer header when requesting refresh:
the resource-server filter still validates headers on public routes.

Logout has no caller-selectable tenant/session ID. It uses the validated JWT sid,
account and tenant; body/header values cannot select someone else's session. Repeating
logout with the revoked access JWT returns 401. If access has expired, refresh once
then logout; an already-expired seven-day session is unusable regardless. Clearing
client credentials alone is not server-side logout.

## Rotation, replay and concurrency

A refresh secret has 256 random bits, canonical unpadded base64url encoding (43
characters); the DB stores only its SHA-256 digest. A known token maps to exactly
one session. Rotation locks that session row, checks active ownership/expiry and
consumption, then signs and atomically stores the successor while consuming its
predecessor. Local signing or transaction failure rolls back issuance/consumption.

If a consumed token is presented again, revoke the whole session and COMMIT before
returning 401. All its old/new access and refresh tokens then fail. This intentionally
fails closed for two concurrent refresh requests: at most one returns a pair, but
the second request's replay revokes that pair as well. Clients must serialize refresh;
do not blindly retry an old token after an ambiguous network failure. Reauthenticate
if the response was lost. No reuse grace period or stored plaintext successor exists.

Other login sessions, including the same user's other device, remain valid. Rotation
alone does not revoke still-unexpired access JWTs in that session; logout, detected
replay or session expiry does. Requests already authenticated/in flight may finish;
subsequent authentication after revocation commits is denied. There is no claim of
cancelling work already running or being a complete OAuth authorization server.

## Identity and lifecycle

Each authenticated request checks required canonical sid, active DB session ownership,
absolute expiry and current active tenant/account/role. A signed sid from another
user/tenant is rejected. No role is trusted from JWT claims. Refresh also verifies
current active ownership; an inactive identity encountered during refresh revokes
that family. Account deletion cascades session/token deletion.

A password change alone does not revoke existing sessions; there is no public password
change/reset endpoint in this increment. Account/tenant disabling blocks access while
inactive; implement explicit session revocation if a later administration flow needs
permanent logout on disable/password reset. RSA signing-key changes invalidate old
access JWTs; opaque refresh tokens are not tied to that key and can mint using the
new key while their DB session is valid. Rotation of keys is not logout-all.

Consumed hashes must be retained until session expiry for replay detection. Automatic
history cleanup is not included; a later bounded retention job may delete expired
sessions (children cascade), never live-family predecessors. Database unavailability
fails closed, with no cached authorization fallback.

## Upgrade

Stop older app instances, back up persistent DB data normally, build and start the
new app. Flyway V3 adds sessions/refresh digests and a composite account/tenant FK;
V1/V2 remain unchanged and existing accounts/credentials are preserved. No dev volume
reset or Flyway repair. Existing JWTs without sid are deliberately rejected: users
must log in again after upgrade. Old application versions are not compatible with
new token semantics; do not run mixed versions or promise an automatic rollback.

## PowerShell use

After login per local-auth.md, keep the pair in $session without printing it:

```powershell
$refreshBody = @{ refreshToken = $session.refreshToken } | ConvertTo-Json
try {
    $session = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/refresh `
        -ContentType 'application/json' -Body $refreshBody
} finally {
    $refreshBody = $null
}
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/logout -Headers $headers
$headers = $null
$session = $null
```

Do not submit the old refresh secret again. Refresh is available independently of
the registration opt-in flag and does not grant registration or other-tenant rights.

## Verification — 2026-11-05

Maven verify requires Docker. Tests cover V2->V3 migration, hashed-only storage,
absolute expiry with controllable clock, role/active changes, replay persistence,
concurrent refresh/logout, signer rollback, legacy/foreign sid and independent sessions.
The demo variant -IncludeRegistration -IncludeSessions expects 25 checks (15 roles,
6 registration, 4 session lifecycle), using disposable DB/keys. CI uses both switches.
Developer execution: **25/25 demo checks passed**. Local Surefire reports confirm
**80 tests** (41 auth, 39 app), zero failures/errors/skips, including the PostgreSQL
migration, HTTP replay/concurrency and signer-rollback tests. Agent compilation,
26 selected unit tests, Spotless and Checkstyle passed before that full run.
Raw secrets were not printed. GitHub CI and PR merge remain pending; local success
does not close the remaining document upload/OpenAPI requirements.

Metrics/logs add bounded refresh_succeeded, refresh_denied and logout_succeeded to
eka.auth.events. There are no token/session/user/tenant ID metric labels or raw
credential/error details in these auth outcome logs.
