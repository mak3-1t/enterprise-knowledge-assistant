# Sprint 4 planning — Hybrid search and bounded agents

Sprint 4 follows the project roadmap's E4/E5 scope: improve retrieval quality and
apply document access policy before adding bounded tool use. Keep tenant and current
document-generation filters in every retrieval route. Never expose document content
from a different tenant through search, citations, logs, or evaluation output.

## Stories and delivery order

| Original ID | Scope | Dependency / evidence |
|---|---|---|
| EKA-19 | PostgreSQL lexical search over source sections | V11 `tsvector` + GIN; authenticated tenant, READY/current generation filters |
| EKA-20 | Hybrid vector + lexical retrieval with RRF | EKA-19; compare ranked results with vector-only on a labeled corpus |
| EKA-21 | Configurable reranking | EKA-20; measure relevance and latency before enabling by default |
| EKA-22 | Document ACL metadata filtering | Define and test policy before any retrieval result reaches the model |
| EKA-23 | Bounded agent/tool calling | Allowlisted tools, input/output limits, tenant checks, deadlines, and audit events |

Implement EKA-19, then EKA-20 and its baseline evaluation. EKA-21 depends on measured
retrieval candidates. EKA-22 must be enforced in retrieval itself, not only in the UI.
EKA-23 stays bounded to approved read-only actions unless a later story authorizes
side effects.

## Definition of done

- PostgreSQL integration tests prove tenant isolation, READY/current-generation
  filtering, deletion behavior, migration from the existing schema, and stable limits.
- A labeled evaluation corpus includes relevant, irrelevant, and cross-tenant cases.
  Report model, corpus, metric, and vector-only vs hybrid results; do not claim an
  improvement without measured evidence.
- Reranking reports before/after relevance and latency. ACL tests prove inaccessible
  documents never become prompts or citations.
- Agent tools are explicitly allowlisted, validated, time-bounded, and covered by
  authorization and failure tests.
- Update ADRs and operational/demo documentation; run full Maven verification and
  local acceptance before release `v0.4.0`.

## Current checkpoint

EKA-19 implementation is on branch `feat/lexical-document-search`. On 2026-11-13,
the developer ran `EmbeddingPersistenceTest`: all 5 tests passed, including Flyway
through V11, tenant-scoped READY retrieval, and soft deletion. The selected unit suite
passed 6/6, and a skip-tests verification passed compile, Spotless, and Checkstyle.
EKA-20 now fuses semantic and lexical chunk rankings with RRF and keeps a vector-only
comparison switch. Its six ranking/search unit tests and nine chat tests passed on
2026-11-13, with Spotless and Checkstyle clean. The PostgreSQL evaluation test is
pending; it compares a labeled lexical-recovery case (expected MRR 0.5 vector-only,
1.0 hybrid). This single fixture is not an aggregate quality claim. EKA-21 through
EKA-23 remain.
