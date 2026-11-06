# ADR-010: Durable content validation and malware scanning

- Date: 2026-11-06
- Status: Locally verified (142 tests, 2026-11-06); merge pending

Keep the modular monolith and original Kafka roadmap. Add a local, transactional
Outbox-to-scan-job handoff now, tracked separately from Kafka publication. PostgreSQL
leases, four-attempt budget and document-generation fencing recover crashes and stop
stale results from overwriting deletion/restoration. Persist DEAD as a local dead-letter
state. Future Kafka delivery must preserve job idempotency; published_at is not consumed
by this local dispatcher.

Require both ClamAV and format validation before handing a document to PROCESSING.
Use INSTREAM without sending filesystem paths to the scanner. Fail closed on errors
and timeouts. Recheck source bytes against the sealed hash. Run structural inspection
in a credential-stripped child JVM with heap and deadline limits; this is a first
isolation step, not a claim of OS/container sandbox completion.

Only CONTENT_VERIFIED, status PROCESSING and job success commit on scan success.
READY remains reserved for the actual parse/chunk/embed pipeline. Reject active PDF,
macro/unsafe DOCX, malformed UTF-8 and bounded-archive violations. Preserve original
Markdown and sanitize only future rendered previews. Show safe error codes, not parser
or antivirus diagnostics, in metadata.

See [implementation and verification](../document-scan.md) for limits, opt-in live-engine
tests, configuration and explicit remaining work.
