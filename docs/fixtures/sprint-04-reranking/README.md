# Sprint 4 synthetic reranking evaluation fixture

This directory contains a non-sensitive, synthetic Vietnamese evaluation fixture.
It is intended for local regression and evaluation-tool validation only; it is not
production EKA content and cannot establish production retrieval quality.

## Files

- `corpus.jsonl`: 30 short policy documents, 10 each across three synthetic tenants.
- `queries.jsonl`: 30 queries: relevant, hard-negative, ambiguous-topic,
  cross-tenant, and no-answer cases.
- `qrels.jsonl`: graded judgments (`2` relevant, `1` partially relevant, `0`
  explicitly judged non-relevant). Unjudged document/query pairs are treated as
  grade 0 by the evaluator and are counted in its report.

IDs are fixture-local strings, not EKA tenant or document UUIDs. An integration
runner must map each fixture document to an uploaded document UUID and each tenant
slug to its local test tenant, then run every query using that tenant's identity.
Do not call `/api/agent` to produce rankings: its citations are model-selected and
are not the complete ranked candidate list.

## Local EKA candidate run

Prepare 30 Markdown files with the required `<doc_id>.md` filenames:

```powershell
.\scripts\prepare-sprint-04-reranking-fixture.ps1
```

Upload each of the three generated tenant folders to a separate local EKA tenant.
Do not mix folders. Use an ADMIN account for each tenant, and wait until all 10
documents in each tenant show `READY`. The endpoint reads through EKA's real
tenant-scoped vector/RRF retrieval; it does not create or index fixture documents.

Use the local upload/parser/Ollama setup from [the Sprint 3 demo](../../sprint-03-demo.md)
so new fixture documents reach `READY`. Keep the embedding model version equal to
the one used for these new documents. Start the app with the evaluation-only profile
and endpoint enabled, while leaving reranking disabled so the export is the RRF
baseline:

```powershell
java -jar .\app\target\app-0.1.0-SNAPSHOT.jar `
  --server.address=127.0.0.1 `
  --spring.profiles.active=dev,evaluation `
  --eka.ingestion.embedding.enabled=true `
  "--eka.ingestion.embedding.model-version=$($env:EKA_INGESTION_EMBEDDING_MODEL_VERSION)" `
  --eka.ingestion.retrieval.hybrid-enabled=true `
  --eka.evaluation.retrieval.enabled=true `
  --eka.ingestion.retrieval.reranking-enabled=false
```

If `EKA_INGESTION_EMBEDDING_MODEL_VERSION` is empty, set it to the exact model
version label used by the fixture's indexed documents before starting the app.

This extra endpoint is disabled unless both the `evaluation` profile and the
property are enabled. Bind the app to loopback as shown; the endpoint requires an
authenticated tenant ADMIN. The export script asks for each tenant's slug, ADMIN
email, and secure password without echoing credentials or candidate text:

```powershell
.\scripts\export-reranking-candidates.ps1
```

It maps the returned EKA filenames (`a01.md`, for example) to fixture-local IDs,
deduplicates chunks by document in their first-ranked order, and writes the actual
baseline candidates to `target/sprint-04-reranker-candidates.jsonl`. Then run the
TEI reranker and evaluator commands below.

## Result format

Provide one result row for every query in `queries.jsonl`:

```json
{"query_id":"q01","rrf":["a02","a01","a08"],"reranked":["a01","a02","a08"]}
```

`rrf` must be the ordered candidate document IDs from EKA's actual vector/RRF
retrieval. `reranked` must be an exact permutation of those same IDs after the TEI
reranker. The evaluator checks complete query coverage, valid unique IDs, tenant
isolation and candidate preservation; it cannot independently prove how the result
file was generated. Pass `--candidate-source` to record the claimed retrieval/model
version with the report.

To call TEI on a candidate export, provide one row per query in this format:

```json
{"query_id":"q01","candidates":[{"doc_id":"a01","tenant_id":"acme_retail","text":"..."}]}
```

`tenant_id` must be the fixture tenant slug after mapping the authenticated EKA
tenant UUID. Candidates outside the query tenant are rejected before the request is
sent to TEI. Then run both stages:

```powershell
python .\scripts\rerank-evaluation-candidates.py `
  --candidates .\target\sprint-04-reranker-candidates.jsonl `
  --output .\target\sprint-04-reranker-results.jsonl
python .\scripts\evaluate-reranking.py `
  --results .\target\sprint-04-reranker-results.jsonl `
  --candidate-source "EKA vector+RRF; model=<model>; revision=<revision>"
```

`benchmark-reranking.ps1` remains a separate TEI latency smoke with a seeded
synthetic baseline; use the EKA candidate export above for retrieval-quality
comparison.

## Acceptance cautions

- The corpus contains only 30 short, clean, synthetic documents. Results apply only
  to this fixture.
- Six cross-tenant and three no-answer queries expect no relevant result. Any
  cross-tenant candidate is a hard failure; the evaluator also reports whether any
  candidate remains for no-answer queries.
- Before using the scores as EKA-21 evidence, obtain complete/approved relevance
  judgments for the candidate pool and run the actual EKA retrieval pipeline.
- Keep reranking disabled by default until the approved corpus-level relevance and
  latency evaluation has been reviewed.
