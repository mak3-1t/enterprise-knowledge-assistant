# Local authentication (#26)

## Prerequisites

Java 21, Docker Desktop, and the database setup in [auth-persistence.md](auth-persistence.md).
Set POSTGRES_USER, POSTGRES_PASSWORD and EKA_DB_URL in the application's process
environment as documented there. Java does not automatically import .env.

## Generate a development signing pair

From the repository root, choose a private local output directory whose ACL permits
only the developer account. Example using an ignored PEM output path:

```powershell
java auth/src/main/java/com/eka/auth/tools/GenerateAuthKeys.java .local/auth-keys
$env:EKA_AUTH_PRIVATE_KEY = ([System.Uri](Resolve-Path .local/auth-keys/private.pem).Path).AbsoluteUri
$env:EKA_AUTH_PUBLIC_KEY = ([System.Uri](Resolve-Path .local/auth-keys/public.pem).Path).AbsoluteUri
$env:EKA_AUTH_ISSUER = "https://eka.local"
$env:EKA_AUTH_AUDIENCE = "eka-api"
```

The utility refuses to overwrite existing keys. Keep the same pair across normal
restarts; changing it invalidates existing tokens. PEM files are Git-ignored. Key
resources must be local file: locations, never pasted key material in an issue or
command argument. Configuration currently accepts Spring Resource locations; use
only files under your control. No network key discovery is configured.

## Explicit dev-only account provisioning

Set these environment variables in the same terminal, then start with profile dev.
Use a locally chosen password of at least 12 characters and at most 72 UTF-8 bytes.

```powershell
$env:EKA_AUTH_BOOTSTRAP_ENABLED = "true"
$env:EKA_AUTH_BOOTSTRAP_TENANT_SLUG = "demo"
$env:EKA_AUTH_BOOTSTRAP_EMAIL = "admin@example.test"
$env:EKA_AUTH_BOOTSTRAP_ROLE = "ADMIN"
$securePassword = Read-Host "Local bootstrap password" -AsSecureString
$env:EKA_AUTH_BOOTSTRAP_PASSWORD = [System.Net.NetworkCredential]::new("", $securePassword).Password
.\mvnw.cmd -B verify
java -jar app/target/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

Environment properties above map to eka.auth.bootstrap.* through Spring's relaxed
binding. The provisioner runs only with dev plus explicit enabled=true, and never
when prod/production is also active. It creates missing records without changing an
existing account's password, role or active flag. Disable bootstrap after first setup
by clearing the enabled/password environment variables. These changes affect future
processes, not an already-running JVM. There is no default production admin.

## Login from another terminal

```powershell
$securePassword = Read-Host "Login password" -AsSecureString
$body = @{
    tenantSlug = "demo"
    email = "admin@example.test"
    password = [System.Net.NetworkCredential]::new("", $securePassword).Password
} | ConvertTo-Json
$session = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
    -ContentType "application/json" -Body $body
$body = $null
$securePassword = $null
# Use $session.accessToken in memory. Do not print it or paste it into chat/issues.
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
Invoke-RestMethod -Uri http://localhost:8080/actuator/info -Headers $headers
```

Login returns accessToken, tokenType=Bearer and expiresIn=900. Invalid credentials
or inactive identity return generic JSON 401; malformed input returns JSON 400.
Passwords are not trimmed. Emails must be valid input; lookup is case-insensitive.
No credentials or tokens are intentionally included in application logs; do not
turn on HTTP-body/security debug logging for real credentials.

## Endpoint access

- POST /api/auth/login: public login route.
- GET /actuator/health and its liveness/readiness paths: public health.
- Other /actuator/** routes: require a valid token and current ADMIN role.
- Other routes: require a valid token; API endpoints not yet implemented may then return 404.
- Missing/invalid/expired token: JSON 401 and Bearer challenge.
- Insufficient role: JSON 403. No HTML form-login redirect or browser session cookie.

GET /api/me and tenant ADMIN member endpoints are documented in [tenant-member-api.md](tenant-member-api.md).
The test-only identity endpoint is absent from packaged code. AuthenticatedAccount / CurrentAccount
is the small public module API used by the production endpoints.

## Roles and upgrades

Supported roles are ADMIN, EDITOR and VIEWER. Dev bootstrap accepts only these
values; existing MEMBER accounts become VIEWER through Flyway V2 on startup.
Account IDs, passwords, active flags and tenant ownership are preserved. Existing
JWTs use the current database role on the next request, without token reissue.
Member administration and non-health actuator routes remain ADMIN-only. Both
EDITOR and VIEWER can read their own /api/me but receive 403 on those routes.
See [role migration and policy](auth-roles.md) before upgrading a local database.

## Tests and limitations

```powershell
.\mvnw.cmd -B verify
```

Tests use ephemeral RSA keys, a fixed/controllable clock and disposable PostgreSQL
containers. No dev accounts, local signing files or external issuer is required.
Token/profile unit tests also run without Docker when explicitly selected; the full
verify suite requires Docker and must pass without skipped tests before merge.

No refresh tokens, registration, password reset or token denylist exists. Disabling
an account/tenant prevents further token use; a password change alone does not revoke
existing tokens. Key rotation, HTTPS deployment and rate limiting remain follow-up
work. See [ADR-004](adr/0004-local-authentication.md) for tradeoffs and scope.

Authentication outcomes are counted in Micrometer eka.auth.events with a bounded outcome label only; no user, tenant or credential labels are recorded. The metrics endpoint is not exposed by default.
