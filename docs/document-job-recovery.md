# Recover a failed document job

A parse or embedding job that exhausts its retries on a transient error marks the
document FAILED and retains its private original and quota reservation for seven days.
Permanent failures keep the existing immediate cleanup policy. Previously purged
documents must be uploaded again.

After resolving the underlying issue, use an authorized database operator account.
Look up the exact tenant, document and FAILED version in the incident. Run from the repo:

```powershell
psql -v "tenant_id=<tenant-uuid>" -v "document_id=<document-uuid>" -v "failed_version=<version>" -f scripts/recover-document-job.sql
```

The statement checks the recovery window and a matching DEAD parse or embedding job.
It atomically starts a new document version, removes old derived sources and vectors,
and records a DOCUMENT_RESTORED event for the scan worker. The original is scanned
again before parsing and embedding. Old job rows remain available for investigation.
The exact version check makes a repeated command return no row and prevents it from
reviving a deleted, purged or already requeued document. The recovery statement and
physical cleanup both lock the document row.

The Kafka intake replay command remains separate. See [parser intake DLT](document-intake-dlt.md).
