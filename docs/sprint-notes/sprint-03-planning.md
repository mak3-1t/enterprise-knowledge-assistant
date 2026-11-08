# Sprint 3 plan

Sprint 3 starts from v0.2.0. The goal is a tenant-scoped chat that answers using
indexed documents, streams results and cites the source chunks.

| Story | Deliverable |
|---|---|
| EKA-13 | Exact pgvector search restricted to the authenticated tenant, current READY generation and matching embedding model |
| EKA-14 | Configurable LLM provider and a prompt assembled from bounded source context |
| EKA-15 | SSE streaming with cancellation and safe terminal errors |
| EKA-16 | Source identifiers, filename, real PDF page where available and validated chunk references |
| EKA-17 | Tenant-owned conversations with bounded history and restart persistence |
| EKA-18 | Configurable relevance threshold and an explicit no-answer path |

Use a minimal chat interface for acceptance. Prepare synthetic documents for two
tenants with conflicting facts, plus a separate evaluation set covering supported
and unsupported questions. Upload the source corpus through the normal ingestion
flow and keep expected answers outside the knowledge base.

## Retrieval checkpoint

The ingestion module exposes a Java retrieval contract for authenticated callers.
The query uses exact cosine distance with deterministic tie-breaking. It applies
tenant, READY state, source generation and model identity predicates before taking
the top ten results. Matches include bounded text slices and their original UTF-16
offsets. The adapter keeps source text out of its diagnostic string representation.
Exact search is the baseline for this sprint. Measure query latency before selecting
an approximate index for larger corpora.

The pgvector integration test covers both tenant directions, model version mismatch,
PROCESSING and deleted documents, result limits and Unicode source offsets. Run:

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-retrieval-verify.log
```

The original schedule allows two weeks. Reassess after the retrieval and provider
contracts are verified. Story IDs in this plan are internal planning references.

Developer verification passed 199 tests on 2026-11-08 at 09:38:19 +07:00, including tenant retrieval on pgvector and live ClamAV.
