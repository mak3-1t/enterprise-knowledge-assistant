# ADR-012: Durable verified-event intake and private parsed source generations

- Date: 2026-11-06
- Status: Developer integration verification passed; merge pending
- Scope: original Sprint 2 EKA-8, following issue #40 / PR #41 Outbox relay

Use a PostgreSQL inbox before committing Kafka offsets. Verify the message against
persisted CONTENT_VERIFIED and current tenant/document version before storage access.
Duplicate deliveries reuse one generation/job. Unknown/invalid input is recorded as
minimal safe rejection metadata; no raw content or arbitrary path is trusted.

Use leased jobs and bounded processing retries independent of Kafka offset ownership.
A child JVM performs explicitly selected Tika parsing with resource bounds. Pin the
maintained Tika 3.3.3 line and only needed modules rather than a catch-all parser bundle.
Disable OCR, external converters and embedded-document recursion for the approved scope.
This provides heap/deadline isolation, not a claim of full OS sandbox completion.

Atomically publish private source sections and a source-generation pointer with a
new document version, SOURCE_PARSED event and job success. Leave status PROCESSING:
READY requires future chunk/embedding success. Delete removes text immediately and
restore requires a newly verified generation. Late workers cannot overwrite deletion.

No public text endpoint, global content deduplication, exactly-once broker guarantee,
automatic dead-letter replay or fabricated page numbers are introduced. Keep Kafka DLT,
full isolation/retention and remaining sprint acceptance work explicit.

See [implementation, configuration and verification](../document-text-parser.md).
