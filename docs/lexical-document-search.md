# Tenant-scoped lexical document search

Sprint 4 foundation for hybrid retrieval. Flyway V11 adds a stored PostgreSQL
`tsvector` to each source section and a GIN index. The `simple` text configuration
handles Vietnamese token boundaries without language-specific stemming. Only the
first 64,000 characters of a source section are indexed; current ingestion sections
are bounded well below this limit.

`LexicalSectionSearch` derives the tenant from the authenticated account. It returns
only current-generation sections belonging to READY, non-purged documents. Query
terms are passed as parameters to `plainto_tsquery`; callers cannot provide raw SQL
or a tsquery expression. Search limits are bounded to 1–50 results.

The chat search path combines bounded vector and lexical chunk rankings with reciprocal
rank fusion (RRF, rank constant 60). It deduplicates by tenant-owned document,
generation, and chunk ordinal. Lexical-only candidates keep their lexical provenance
so the existing vector similarity floor does not discard exact lexical evidence;
the model still must answer from the supplied evidence and may decline. Set
`EKA_HYBRID_SEARCH_ENABLED=false` to retain the vector-only baseline for comparison.
Do not infer production relevance from the small synthetic Sprint 3 corpus.

## Verification

`EmbeddingPersistenceTest` covers readiness, tenant isolation, mixed-case queries,
no-match behavior, chunk evidence, and soft deletion. `ReciprocalRankFusionTest` and
`RrfDocumentSearchTest` cover rank promotion, deduplication, bounded output, and the
vector-only switch. Run the PostgreSQL-backed test with:

```powershell
.\mvnw.cmd -B -pl ingestion -am '-Dtest=EmbeddingPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

The test requires Docker/Testcontainers. A successful compile or a run that cannot
connect to Docker does not count as integration verification. EKA-20's quality
comparison must record MRR/nDCG and latency on a labeled corpus before claiming that
hybrid search improves relevance.

The integration suite now includes one deliberately difficult labeled case: an
irrelevant chunk is made the nearest vector result while the relevant policy chunk
contains all query terms. It asserts vector-only MRR 0.5 and hybrid MRR 1.0 for that
fixture. This is a regression check for lexical recovery, not a corpus-wide quality
claim; expand the labeled set before describing aggregate relevance gains.
