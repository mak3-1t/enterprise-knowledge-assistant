# Tenant identity and member APIs (#28)

## Prerequisites

Use [local authentication](local-auth.md) to configure PostgreSQL, signing keys
and explicit dev accounts. Send the access token in the Authorization: Bearer header.
Tenant identity and role come from the verified principal and active database records,
never from a client-supplied tenant header, query parameter or request body.

## HTTP contract

| Request | Access | Result |
|---|---|---|
| GET /api/me | Active authenticated ADMIN, EDITOR or VIEWER | 200 with userId, tenantId, email and current role |
| GET /api/tenant/members | Current tenant ADMIN | 200 with items, page and size |
| GET /api/tenant/members/{id} | Current tenant ADMIN | 200 with a safe member summary; absent or foreign ID gives identical JSON 404 |

Member summaries contain id, tenantId, email, role, active and createdAt. There are
no credential/password-hash fields. Disabled accounts remain visible to their own
tenant ADMIN for administration, but cannot authenticate. A tenant ADMIN has no
platform-wide access and cannot select another tenant through these routes.

Successful identity/member responses and controller errors use Cache-Control:
no-store. Unauthorized requests return JSON 401, insufficient roles JSON 403,
and missing/foreign IDs both return {"error":"not_found"}. Malformed UUIDs or
pagination parameters return {"error":"invalid_request"} with 400 for ADMIN.
EDITOR and VIEWER are rejected with 403 before member ID/query binding or data access.

## Pagination

Use zero-based page (default 0) and size (default 20, range 1..100).
Rows are filtered by tenant in SQL before ORDER BY id, LIMIT and OFFSET. The UUID
order is deterministic, not creation-time order. page must be non-negative and
page * size must fit a signed 32-bit SQL offset; overflow is rejected with 400.
Large values are not silently clamped. Pages beyond the available rows return an
empty items array. Responses contain no total count or cross-tenant statistics.
Offset pagination can move if data changes between requests; no snapshot is promised.

Example response:

```json
{
  "items": [],
  "page": 0,
  "size": 20
}
```

## PowerShell requests

After login using the instructions in local-auth.md, keep the token in memory:

```powershell
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
Invoke-RestMethod http://localhost:8080/api/me -Headers $headers
$page = Invoke-RestMethod 'http://localhost:8080/api/tenant/members?page=0&size=20' -Headers $headers
$page.items
# Choose an account ID returned in the current tenant's list.
$memberId = $page.items[0].id
Invoke-RestMethod "http://localhost:8080/api/tenant/members/$memberId" -Headers $headers
```

Member routes require ADMIN; EDITOR and VIEWER can still call /api/me. To verify isolation,
use an ID obtained separately while signed in to tenant B, then request it with
tenant A's ADMIN token. The result must match a random nonexistent ID: JSON 404.
Do not post live tokens, account lists or email addresses in logs/issues.
A full scripted two-tenant acceptance demo belongs to S1-04.

## Implementation and verification

IdentityService reads CurrentAccount, enforces ADMIN before member repository calls,
and maps internal persistence summaries to explicit response records. The HTTP
security chain also guards the member route family, including malformed member IDs.
No unscoped lookup/list is added. There are no schema changes, write/member-management
endpoints or client-selectable role/tenant fields in this story.

The existing eka.auth.events counter gains bounded outcome labels: me_read,
members_listed, member_read and member_not_found. Logs/metrics contain no caller
IDs, target IDs, emails, credentials, arbitrary URLs or tenant labels. Existing
unauthorized/forbidden outcomes cover security denials.

```powershell
.\mvnw.cmd -B verify
```

Unit tests verify service-level role checks before database access, scope/offset
arguments, safe identity mapping and pagination overflow. PostgreSQL-backed HTTP
tests cover A/B lists and IDs, EDITOR/VIEWER denials, inactive/expired identities,
forged headers/query/body, default and maximum pages, role changes and concurrent
interleaved requests. They extend the existing disposable-container/key fixtures.
The persistent dev database, external identity providers and Ollama are not required.
These checks prove account API isolation; document ACLs and database RLS remain separate work.
