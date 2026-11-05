# Auth persistence foundation (#24)

## Ownership and scope

The auth module owns `auth.tenants` and `auth.accounts`. V1 creates the schema,
UUID primary keys, tenant/account foreign key, timestamps, active flags and the
original ADMIN/MEMBER role constraint. V2 upgrades roles to ADMIN/EDITOR/VIEWER,
converting existing MEMBER to VIEWER (see [migration guide](auth-roles.md)). Tenant slug is a unique lower-case URL-style name.
Account email must be trimmed/lower-case and unique within a tenant; the same
email may occur in a different tenant. A tenant with accounts cannot be deleted
without explicitly handling those accounts (no cascading deletion).

Passwords belong only in `password_hash`; application hashing/login is a later
story. V1 rejects an empty hash but does not implement password hashing or validate
hash algorithms. It inserts no tenants, accounts, credentials or default admin.
The test-only hash placeholder is not a usable login credential.

## Migrations

Flyway is the only schema initializer. The migration lives at
`auth/src/main/resources/db/migration/V1__auth_tenants_and_accounts.sql`; its history
is kept in the default public schema. Versions are application-wide: reserve the
next unique version when another module adds a migration. Never edit an already
applied migration; add a new migration. Flyway clean is disabled, and no automatic
baseline is enabled. No JPA schema generation, schema.sql or runtime DDL is used.
Do not delete the existing Docker volume to test migrations.

## Repository contract

The internal JDBC repository uses named/positional bound parameters, not interpolated
user values. `findById(tenantId, accountId)` and `findMembers(tenantId, limit, offset)`
require tenant scope. Listing has deterministic UUID ordering, limit 1..100 and
non-negative offset. UUID ordering is only for stable pagination, not creation order.
There is no unscoped account lookup or list API.

The one credential lookup is constrained by both tenant slug and normalized email.
Credential results include tenant/account active flags for the next auth story to
enforce; persistence alone does not authenticate or authorize a caller. Credential
records redact their toString output, and normal account summaries omit hashes.
Keep these classes within auth; future modules must use a small auth-owned public
service API rather than querying auth tables or accepting a caller-supplied tenant.
Database row-level security and document ACLs are not delivered by this story.

## Run locally

Requirements: Java 21 and Docker Desktop using Linux containers. Prepare `.env`
from `.env.example` and set real local values. From the repository root:

```powershell
docker compose --env-file .env -f deploy/docker/docker-compose.yml up -d postgres

# Compose reads .env itself; Java does not. Import only these simple, unquoted
# key=value entries into this PowerShell session without printing their values.
Get-Content .env | ForEach-Object {
    if ($_ -match '^(POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_DB)=(.*)$') {
        Set-Item -LiteralPath "Env:$($Matches[1])" -Value $Matches[2]
    }
}
$env:EKA_DB_URL = "jdbc:postgresql://localhost:5432/$env:POSTGRES_DB"
.\mvnw.cmd -B verify
java -jar app/target/app-0.1.0-SNAPSHOT.jar
```

The application now requires a reachable database and database credentials; it
fails startup if configuration/migration fails. Use `EKA_DB_URL` to override the
host/port/database. The default URL is localhost:5432/eka. No password default is
provided. Configurations and connection strings containing credentials must not be
committed. Restarting the application validates migration history without dropping
or recreating application tables.

In another terminal, `curl.exe http://localhost:8080/actuator/health` should return
UP after startup. Missing local credentials or unreachable PostgreSQL must be fixed
rather than bypassing Flyway. Login endpoints are not implemented yet.

## Integration tests and CI

```powershell
.\mvnw.cmd -B verify
```

Auth repository tests and the app startup/restart test each use a fresh disposable
`postgres:17-alpine` Testcontainer with a random host port. They never connect to the
persistent Compose database or require your .env, Ollama, Kafka, Redis or S3.
Testcontainers manages cleanup; container reuse is not enabled. Docker and the
container image registry must be reachable. Missing Docker fails the build; these
tests are not silently skipped.

The auth suite checks migration replay preserving data/history, database constraints,
email uniqueness/normalization, cross-tenant ID/list/credential queries in both
directions, safe credential representation, inactive flags and pagination bounds.
The app test verifies actual Boot/Flyway/JDBC wiring and starts a second application
context against the same temporary database to prove persisted data survives restart.
These are persistence tests, not proof of future HTTP authorization.

CI keeps the existing Repository checks and Build and test names. The latter checks
Docker readiness and runs all tests in Maven verify, then verifies the framework spike.
Do not use skipTests, disable Docker tests or supply the dev database to make CI pass.

## References

- https://docs.spring.io/spring-boot/reference/data/sql.html
- https://docs.spring.io/spring-boot/how-to/data-initialization.html
- https://java.testcontainers.org/modules/databases/postgres/
