# ADR-001: Modular monolith first, microservices later

- Status: Accepted
- Date: 2026-11-01

## Context
Enterprise Knowledge Assistant has several distinct capabilities: authentication and
tenants, document ingestion, RAG chat, agent tool calling, and evaluation. The long-term
target architecture may be microservices, but the project is built by one developer in
about 12 weeks. Early on, the boundaries between these capabilities are not yet proven,
and there is no operational capacity for many deployables.

## Decision
Build a **modular monolith** first: one deployable application (`app`) composed of
separate Maven modules (`common`, `auth`, `ingestion`, `chat`, `agent`, `evaluation`).

Rules that keep the boundaries real:
- A module exposes a small public API (interfaces, DTOs, events). Other modules must not
  use its internal classes.
- Modules communicate through those APIs or through events, not through shared database
  tables owned by another module.
- Long-running work (ingestion) is designed as asynchronous and event-driven, so it can
  later move behind Kafka without redesign.
- Boundary checks (for example ArchUnit tests) will be added once there is code worth
  protecting.

## Consequences
Positive:
- One build, one deployment, one process to debug. Faster delivery for a solo developer.
- Refactoring across modules is cheap while the design is still moving.
- Lower infrastructure and operational cost during development.

Negative:
- All modules scale and fail together.
- Discipline is required: without enforced boundaries a modular monolith decays into a
  tangled one.
- A shared runtime means a heavy module (for example embedding generation) can starve
  others until it is split out.

## When to split into services
A module becomes a separate service only when there is a measured reason, such as:
- It needs to scale independently (expected first candidate: ingestion, because
  embedding generation is CPU and I/O heavy).
- It needs a different release cadence, runtime or failure isolation.
- Its module boundary has stayed stable and its API is already event-based.

The split decision will be recorded in a new ADR with the measurements that justify it.

## Alternatives considered
- **Microservices from day one:** matches the long-term vision but multiplies build,
  deployment, observability and testing cost before the boundaries are known.
- **Classic single-module monolith:** simplest, but gives no structural pressure to keep
  capabilities separated, which makes a later split expensive.