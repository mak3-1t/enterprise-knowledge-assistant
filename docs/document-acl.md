# Document role access

Documents are shared with all members of their tenant by default. An ADMIN can
restrict a document to EDITOR and/or VIEWER roles; ADMIN access is implicit and
cannot be removed. An empty reader list makes the document ADMIN-only. Upload
ownership remains separate: EDITOR can delete only their own uploads, while ADMIN
can manage every document in the tenant.

## Configure access

After authenticating as an ADMIN, use the document list in the browser UI or call:

```http
GET /api/documents/{documentId}/access
PUT /api/documents/{documentId}/access
Content-Type: application/json
Authorization: Bearer <access-token>

{"readableBy":["VIEWER"]}
```

`readableBy` accepts a distinct subset of `EDITOR` and `VIEWER`. The default is
`["EDITOR","VIEWER"]`; `[]` restricts access to ADMIN. Non-ADMIN callers receive
403 when managing ACLs. A caller without read access receives the same 404 as a
missing document from list/detail/download routes to avoid revealing its existence.

## Enforcement

Flyway V12 adds `reader_roles`, defaults all existing documents to tenant-wide
access, and adds a GIN index for role filtering. The authenticated role is resolved
from the verified account, never from the request. PostgreSQL applies the same
ACL condition to document listing, details, downloads, vector search, and lexical
search. Hybrid RRF and the optional reranker only receive already-authorized
candidates, so restricted evidence cannot enter the prompt or citations.

Workers and lifecycle operations continue to use tenant/document identity. ACL
changes do not increment the ingestion version or invalidate an in-flight parse or
embedding job. Changing the reader roles takes effect on the next request.
