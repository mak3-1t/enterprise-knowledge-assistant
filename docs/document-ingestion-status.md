# Document ingestion status

Active ADMIN, EDITOR and VIEWER accounts can read status for documents in their own tenant
through `GET /api/documents/{id}` and the paginated `GET /api/documents`. Both routes return
the same metadata fields and use `Cache-Control: no-store`. Foreign, missing, deleted and
expired documents return the same 404 on detail. Missing or inactive identities receive 401.

| Status | Meaning |
|---|---|
| PENDING | Upload intent is open and completion has not been confirmed |
| UPLOADED | The private original is sealed and scan work is queued |
| SCANNING | Safety and format checks are underway |
| PROCESSING | A verified original is being parsed, split or embedded |
| READY | The full vector set committed for the current source generation |
| FAILED | Processing ended without publishing a complete vector set |
| REJECTED | The upload failed a safety or format check |

`version` is the current document revision. It increases on lifecycle transitions,
including restore and operator recovery. A version can also advance while the status remains
PROCESSING. Clients can discard an older polling response if it arrives out of order.

`errorCode` is null outside FAILED and REJECTED. Those states expose allowlisted reason codes
such as MALWARE_DETECTED, EXTRACT_EMPTY or EMBEDDING_RETRY_EXHAUSTED. Unknown diagnostics
map to PROCESSING_FAILED. Metadata omits private object keys, document contents, provider
responses and worker details. Download still requires READY.

Retries and leases are durable in PostgreSQL. An expired worker cannot overwrite a newer
generation. A retained transient failure can be requeued by an authorized operator
within seven days. See [job recovery](document-job-recovery.md).

Run the Docker verification:

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/document-status-verify.log
```
