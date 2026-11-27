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

EKA-19 is merged. On 2026-11-13,
the developer ran `EmbeddingPersistenceTest`: all 5 tests passed, including Flyway
through V11, tenant-scoped READY retrieval, and soft deletion. The selected unit suite
passed 6/6, and a skip-tests verification passed compile, Spotless, and Checkstyle.
EKA-20 now fuses semantic and lexical chunk rankings with RRF and keeps a vector-only
comparison switch. Its six ranking/search unit tests and nine chat tests passed on
2026-11-13, with Spotless and Checkstyle clean. The PostgreSQL evaluation test passed
6/6 on 2026-11-13; its labeled lexical-recovery case reports MRR 0.5 vector-only and
1.0 hybrid. This single fixture is not an aggregate quality claim. EKA-21 is now
implemented behind an opt-in TEI reranker adapter, with bounded payloads, validated
scores, timeout, fallback to RRF, and low-cardinality metrics. Mock-based tests do not
replace model-on-corpus relevance/latency measurement; keep reranking disabled until
that measurement is recorded. EKA-22 implements tenant-wide default sharing with
ADMIN-managed role restrictions, enforced in document reads and retrieval. EKA-23
merged in PR #80 with a read-only document search tool, tenant and role-aware retrieval,
bounded tool arguments/results, a fixed worker pool, an overall deadline, citation
validation, and tool audit events. Agent unit tests and application compilation passed.

On 2026-11-15, the installed Ollama model advertised tool support and a direct Ollama
API smoke produced a `search_documents` tool call. On 2026-11-23, the developer's
local app smoke passed with an evidence-backed answer and one citation. The TEI fixture
smoke also completed with `gte-multilingual-reranker-base` revision
`8215cf04918ba6f7b6a62bb44238ce2953d8831c`: over four synthetic queries and five
iterations per query, seeded-order MRR/nDCG@3 was 0.5/0.5413 and reranked MRR/nDCG@3
was 1.0/0.8163; reranker HTTP p50/p95 was 416/516.1 ms. This confirms local wiring
and a fixture-level regression only, not production retrieval improvement. See
`docs/agent-tools.md` and `docs/reranking.md`. Sprint 4 remains open until relevance
and latency are measured on an approved labeled corpus using actual vector/RRF
candidate sets. Reranking stays disabled by default; do not release `v0.4.0` yet.

On 2026-11-20, `BoundedAgentServiceTest` passed 6/6 after adding regressions for the
server-cited excerpt fallback when the model omits a citation and for preserving an
explicit `NO_EVIDENCE`. Spotless and Checkstyle passed. This verifies the bounded agent
unit path only; the live app smoke is now recorded above, while corpus-level reranker
evaluation remains open.

On 2026-11-23, a synthetic labeled fixture was added at
`docs/fixtures/sprint-04-reranking/` (30 documents, 30 queries, three tenants).
The opt-in `evaluation` profile now exposes tenant-admin-only access to actual
vector/RRF candidates; a PowerShell exporter maps uploaded `<doc_id>.md` fixture
documents into the TEI and evaluator inputs. `/api/agent` citations remain
model-selected and are not valid full retrieval rankings. This tooling is runnable,
but no actual EKA candidate comparison has been recorded yet; Sprint 4 remains open.
