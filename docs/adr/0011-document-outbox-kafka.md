# ADR-011: At-least-once document Outbox relay to Kafka

- Date: 2026-11-06
- Status: Locally verified (160 tests, 2026-11-06); CI/merge pending
- Scope: first Sprint 2 delivery, original planning reference EKA-7 (not an issue number)

## Decision

Publish the existing committed document Outbox to a versioned Kafka event topic.
Keep local scan dispatch independent: scan_dispatched_at tracks local work, while
published_at is set only after a Kafka broker acknowledgement and a valid lease.

V7 adds relay ownership, retry state, broker receipts and a composite tenant/document
FK. Each event has an immutable UUID identity, schema version 1, tenant/document IDs,
document version, type and original timestamp. It contains no text, filename, storage
key, URL, credential or arbitrary Java type information. Partition key is tenant/id.

Claim one event in a short PostgreSQL transaction using SKIP LOCKED and a random
45-second lease. Publish outside the DB transaction. On broker acknowledgement, record
the actual partition/offset only if ownership remains current and unexpired. Producer
uses acks=all and idempotence with bounded send/block deadlines. These Kafka settings
do not provide exactly-once DB/broker delivery across crashes.

Crash before broker append leaves a retryable row. Crash/ack loss after append can
cause duplicate publication with the same eventId; consumers must deduplicate eventId
and fence document generations. Later events for the same document wait for earlier
database acknowledgement. Delayed duplicates from stale in-flight producers can still
arrive out of order, so partitioning is not a substitute for generation validation.

Retry relay failures indefinitely with exponential delay capped at 60 seconds; never
drop a committed event after an arbitrary count. Processing retry budgets/Kafka DLT
belong to the upcoming consumer stories. Operators must monitor backlog age and stalled
documents. Current delivery provides bounded outcome counters/logs; alerting and full
retention/replay tooling remain pending.

## Alternatives

- DB commit then direct Kafka send loses work if the process dies between operations.
- Kafka send then DB commit can publish events for rolled-back/unavailable documents.
- A shared DB/Kafka transaction is not available here; producer idempotence alone
  cannot close the cross-system crash window.
- CDC/Debezium is a viable later relay replacement but introduces connector/replication
  operations beyond the current local infrastructure. Preserve event IDs/schema if used.

## Consequences

At-least-once delivery is explicit. All existing unpublished V4-V6 events are eligible
after enabling the relay; this catch-up can include stale/deleted document versions.
Consumers must reload current authorized document state before any storage access.
Only a matching CONTENT_VERIFIED event may start parsing. No parse/chunk/embed/READY
implementation or consumer acknowledgement is claimed by this publisher story.

See [configuration and verification](../document-outbox-kafka.md).

## References

- https://docs.spring.io/spring-kafka/reference/quick-tour.html
- https://kafka.apache.org/42/configuration/producer-configs/
- https://java.testcontainers.org/modules/kafka/
