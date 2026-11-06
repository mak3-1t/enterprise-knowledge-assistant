# Document Outbox to Kafka

Status: first Sprint 2 story locally verified; issue #40 created; PR/CI/merge pending.
The preceding upload/scan delivery is merged in PR #39 and passed 142 local tests.
This story implements original planning reference EKA-7, not a GitHub issue number.

## Flow and boundaries

Upload/restore/delete/clean-scan transactions already create document_outbox rows.
V7 adds relay leasing, attempts, bounded retry delay, partition/offset receipts and
tenant/document FK consistency. The publisher is independent of the local scan worker;
it never sets scan_dispatched_at, and local scan dispatch never sets published_at.

A relay claims committed events with SKIP LOCKED, commits the claim and publishes to
Kafka without holding a database transaction over network I/O. Only a successful Kafka
ack plus current unexpired lease sets published_at. Lease expiry recovers crashes.
Failed broker calls leave the event durable and retry with exponential backoff capped
at 60 seconds. There is no silent drop/max-attempt removal of committed relay intents.

Retries after an uncertain Kafka acknowledgement can duplicate an event. The same
eventId, identity/version and occurredAt are reused. Producer idempotence limits some
within-producer duplicates; it does not make the DB/broker boundary exactly once.
Later same-document rows wait for earlier database acknowledgement. Consumers still
need duplicate detection and generation checks because stale in-flight deliveries can
arrive late. No consumer or parser is introduced here.

## Event contract v1

Topic default: eka.documents.events.v1. Key: <tenantUUID>/<documentUUID>.

| Field | Meaning |
|---|---|
| schemaVersion | integer 1 |
| eventId | stable UUID of the Outbox row, reused on retries |
| tenantId / documentId | verified server identities, constrained in PostgreSQL |
| documentVersion | nonnegative version from the state transition |
| eventType | UPLOAD_COMPLETED, DOCUMENT_RESTORED, DOCUMENT_DELETED, CONTENT_VERIFIED |
| occurredAt | original UTC timestamp, not relay attempt time |

There is no filename, storage key/URL, document text, token or credential in the event.
JSON uses string serializers; no Java class/type-header deserialization is needed.
Unsupported future schema versions must be handled explicitly by future consumers.

Only CONTENT_VERIFIED can authorize a future parse attempt, and even then the consumer
must match event identity against the persisted Outbox and current tenant/document
PROCESSING version before storage I/O. Never accept a client/broker-provided file path.
Duplicate processing must use event/generation uniqueness and atomic publication.

## Enable in development

EKA_INGESTION_KAFKA_ENABLED defaults false. Existing auth/upload/scan tests and startup
do not require Kafka just because the starter is present. Standard spring.kafka.*
producer/security/SSL/SASL properties are retained; mandatory acks/idempotence and send
bounds are enforced by the dedicated factory. Local broker defaults localhost:9092.

    docker compose --env-file .env -f deploy/docker/docker-compose.yml up -d kafka

Set EKA_INGESTION_KAFKA_ENABLED=true and EKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092.
For local topic provisioning only, set EKA_KAFKA_CREATE_TOPIC=true (three partitions,
replication factor one). Default false leaves provisioning to operators. Production
requires an appropriate replication/min-ISR policy and broker ACL/TLS configuration;
the one-broker development topology is not a production availability claim.
EKA_DOCUMENT_EVENTS_TOPIC can override the default valid topic name.

Producer delivery timeout is 15 seconds, metadata/block timeout 5 seconds and the relay
waits at most 20 seconds for the future. A 45-second lease leaves recovery margin.
Relay scheduler is separate from upload/scan scheduling. Fixed outcome labels
outbox_published/outbox_deferred/outbox_stale use eka.upload.events; document IDs appear
only in logs, never as metric labels. Broker error details/payloads are not logged by
the application error path. Backlog/age alert rules remain a follow-up task.

## Verification

Unit tests cover stable/public envelope, receipt handling, broker failure, stale
ownership, DB acknowledgement failure and Boot Kafka auto-configuration compatibility.
PostgreSQL tests cover uncommitted/rollback invisibility, concurrent relay claims,
lease recovery, same-document ordering/backoff and tenant identity constraints.
Real PostgreSQL/Kafka integration tests verify append/receipt and simulated crash
after broker append before DB acknowledgement, demonstrating duplicate delivery with
the same event ID rather than an exactly-once claim.

Run the full suite with Docker Desktop running:

    & "C:\Users\Thang\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe" -NoProfile -File .\scripts\verify-outbox-kafka.ps1

The helper reuses ClamAV verification and includes the real Kafka Testcontainer. It
starts/waits for the local ClamAV profile and restores temporary test environment
variables. Log: target/outbox-kafka-verify.log. Full developer run: 160 tests passed with live ClamAV enabled. Without the explicit
live flag, ClamAvLiveTest remains an opt-in skip. No production Kafka credentials,
models or embedding service are needed for these deterministic tests.

Migration regression expects V1..V7; V1..V6 are unchanged. Existing document/scan data
is preserved and old unpublished Outbox rows catch up when enabled. PostgreSQL receipt
does not establish that a downstream consumer processed the event. Event/job retention,
Kafka consumers/DLT, parse/chunk/embed, READY publication and Sprint 1 remaining DoD
gates are not completed by this delivery.

### Agent verification checkpoint

Selected unit regression: 67 tests passed, zero failures/errors/skips. All modules
and test sources compiled; Spotless/Checkstyle passed (BUILD SUCCESS, 2026-11-06
17:29:58 +07:00). Script syntax and new documentation links passed. PostgreSQL/Kafka
execution was subsequently verified in the full developer run recorded below.

### Full developer verification — 2026-11-06

scripts/verify-outbox-kafka.ps1 completed with BUILD SUCCESS at 17:34:39 +07:00
(total 2m46s). Surefire XML totals: auth 41, ingestion 75, app 44; 160 tests passed,
zero failures/errors/skips. Real PostgreSQL/Kafka tests verified committed publication
and duplicate event IDs after simulated broker-ack/DB-ack crash. Live ClamAV, S3, HTTP,
migration V1..V7 and style gates also passed. This is local evidence; CI/merge and
consumer/parse/chunk/embed/READY delivery remain pending.
