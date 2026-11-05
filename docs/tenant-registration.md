# Tenant registration

## Contract and explicit opt-in

POST /api/auth/register creates a new tenant and its first ADMIN. It is closed by
default. Set EKA_AUTH_REGISTRATION_ENABLED=true in the app process environment
(or eka.auth.registration.enabled=true) and restart only when self-registration is
intended. With the flag off, a valid request returns JSON 404 and writes nothing.
Malformed/invalid input can return 400 during request binding even when disabled.
There is no role/header/tenant parameter that bypasses the server-side switch.

Enabling the route allows anonymous callers to create independent tenants; it does
not prove domain ownership or verify email. This opt-in is intended for controlled
local/demo use. Public abuse controls, invitations and email verification are not
implemented; tenant rate limiting belongs to later roadmap work. Disable the switch
again to close new registration without disabling existing accounts/login.

The dev bootstrap is independent: it remains explicitly opt-in/dev-only and does
not overwrite existing credentials. Never expose its implementation as a public API.

## Request and response

```json
{
  "tenantSlug": "example-team",
  "email": "admin@example.test",
  "password": "<locally-chosen-password>"
}
```

Slug/email are stripped and lowercased with Locale.ROOT before validation. Slug is
1..63 characters, lowercase letters/digits separated by single hyphens. Email must
pass Jakarta email validation and fit 254 characters. The same email is allowed in
different tenants. Password is not trimmed: at least 12 characters, nonblank, at most
72 characters AND 72 UTF-8 bytes for BCrypt. No default password is supplied.

Success is 201 with tenantId, userId, tenantSlug, email and role=ADMIN. IDs and role
are server-assigned. Extra role/tenantId/userId request fields cannot select an
existing tenant or alter the new ADMIN role. No access/refresh token is returned:
use POST /api/auth/login after registration. No schema migration is needed for this
story; V1/V2 already contain the required tenant/account constraints.

| Situation | Status | JSON error |
|---|---|---|
| Valid request, switch off | 404 | not_found |
| Invalid/malformed input | 400 | invalid_request |
| Duplicate normalized tenant slug | 409 | registration_conflict |
| Database failure | 503 | registration_unavailable |

Responses use Cache-Control: no-store. Conflict responses do not identify a specific
email/account; a slug conflict does reveal that the requested tenant name is not
available. Repeating registration never resets an existing password or joins that
tenant. Login failures remain generic, as documented in local-auth.md.

## Atomicity and safe outcomes

Validation and BCrypt hashing run before a short repository transaction. Tenant and
first ADMIN commit together or both roll back; DB uniqueness handles concurrent
registrations. Never implement this as a precheck followed by unprotected inserts.
A success event is recorded only after the repository transaction has committed.

The existing eka.auth.events counter gains bounded outcomes registration_succeeded,
registration_disabled, registration_conflict, registration_invalid and
registration_failed. Logs contain these outcomes only, not request bodies, emails,
passwords/hashes, token contents or raw database error details. Request/response
records redact toString. Do not enable HTTP body/SQL parameter debug logging.

## Local setup and PowerShell request

Use docs/local-auth.md for DB and signing-key setup. Java does not automatically
read .env; the variable must be present in the process that starts the app.

```powershell
$env:EKA_AUTH_REGISTRATION_ENABLED = 'true'
java -jar app/target/app-0.1.0-SNAPSHOT.jar
```

From a second terminal:

```powershell
$secret = Read-Host 'New ADMIN password' -AsSecureString
$payload = @{
    tenantSlug = 'example-team'
    email = 'admin@example.test'
    password = [Net.NetworkCredential]::new('', $secret).Password
} | ConvertTo-Json
try {
    $registered = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/register `
        -ContentType 'application/json' -Body $payload
    # Keep response in memory; login using the existing documented flow.
} finally {
    $payload = $null
    $secret = $null
}
```

Remove/set the enable flag to false and restart to close registration. The packaged
demo uses a temporary DB and child-process settings, so it does not open registration
on an already-running local app or overwrite persistent developer data.

## Verification — 2026-11-05

Agent verification: all modules/test sources compiled, 22 selected unit tests passed,
Spotless and Checkstyle passed. Developer then ran the complete Docker-backed suite:
Maven BUILD SUCCESS in 1 minute 29 seconds; Surefire reports confirm **65 tests**
(36 auth, 29 app), with zero failures/errors/skips.

Developer packaged-app demo with -IncludeRegistration: **21/21 PASS**. It verifies
closed-by-default registration, explicit opt-in creating ADMIN, login/identity,
generic duplicate 409, registered-tenant isolation both ways and closing registration
without disabling existing login. Tests additionally cover rollback, concurrent
duplicates, input limits and forged IDs/roles.

The default demo without IncludeRegistration remains the 15-check role regression;
CI runs the 21-check variant. This story's GitHub CI and merge remain pending.
These results cover registration/auth only, not refresh tokens or document upload.
