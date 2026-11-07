# Parser intake dead-letter topic

Set `EKA_DOCUMENT_PARSER_DLT_ENABLED=true` on the parser consumer to enable dead-letter
recovery. The default topic is `eka.documents.parser.dlt.v1`. Override it with
`EKA_DOCUMENT_PARSER_DLT_TOPIC`. Provision the topic and ACLs before enabling it in
production. The development topic provisioning flag creates a three-partition topic.
Only the application producer should write this topic. Limit read access to operators.

The consumer records malformed, unverified and stale inputs in the existing PostgreSQL
rejection ledger, then publishes a DLT notice. A transient intake error gets three retries
one second apart before recovery. Offsets advance after the DLT append is acknowledged.
When the broker cannot write the DLT, the original record remains uncommitted. A crash
after append and before offset commit can duplicate notices. Deduplicate using the stable
original topic/partition/offset key.

The DLT contains a schema version, original Kafka coordinates and a bounded reason.
For an intake outage on a structurally valid CONTENT_VERIFIED record it can also include
an event ID hint. It excludes the original message key, body, headers and exception
details. When DLT is disabled, existing rejection ledger and database outage retry
behavior apply.

## Operator replay

After fixing the intake problem, use an authorized PostgreSQL operator account and an
event UUID matched against the canonical Outbox. Pass connection details through existing
PostgreSQL client configuration and run:

```powershell
psql -v "event_id=<canonical-event-uuid>" -f scripts/replay-document-intake.sql
```

Replay locks the document, verifies the event type and current PROCESSING generation, and
inserts at most one parse job. A repeated request, stale event or deleted document returns
no row. Treat the event ID hint as untrusted until verified in the Outbox. Invalid,
unverified or stale messages require investigation instead of replay. Use the existing
operator database audit trail for this action.

This delivery covers Kafka intake failures. Parse and embedding worker terminal failures
remain recorded in their job tables and are outside this replay command.

Run the Docker verification before publishing:

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/document-dlt-verify.log
```
