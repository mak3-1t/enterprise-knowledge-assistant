# Sprint 0 planning

Recorded on 2026-11-02 from repository history and GitHub issues. The selection
below reconstructs the delivered foundation scope; it is not a record of a
planning meeting or an assertion of the original sprint dates.

## Sprint goal

Prepare a reproducible Java foundation, local infrastructure and AI development
environment, select an AI framework, and establish quality and delivery rules
before implementing tenant-aware document ingestion and RAG.

## Selected stories and outcomes

| Issue | Scope | Evidence at review on 2026-11-02 |
|---|---|---|
| #5 | Java 21 / Spring Boot 4 multi-module skeleton | Merged in PR #13; issue closed |
| #6 | PostgreSQL + pgvector, Redis, Kafka and S3-compatible local infrastructure | Merged in PR #17; issue closed; ADR-003 records storage choice |
| #7 | Maven CI, tests and JaCoCo coverage | Merged in PR #16; issue closed |
| #8 | Spotless and Checkstyle quality gates | Merged in PR #20; issue closed |
| #9 | Modular monolith architecture decision | Merged in PR #18; issue closed; ADR-001 |
| #10 | Spring AI versus LangChain4j spike | Merged in PR #21; issue closed; ADR-002 and local sample |
| #11 | Local Ollama chat and embedding setup | Merged in PR #19; model names and measurements documented; issue closure pending |
| #12 | Planning, Ready/Done definitions and retrospective | This documentation change; awaiting review, CI and merge |

The repair of accidentally tracked Maven build output was completed separately
in issue #14 / PR #15. GitHub PR and issue numbers differ; use their actual links.

## Dependencies and decisions

- Build a modular monolith first; split only with measured reasons (ADR-001).
- Use Spring AI for the existing Spring Boot application (ADR-002).
- Use the S3-compatible local storage selected in ADR-003.
- Use qwen3:4b-instruct-2507-q4_K_M for local chat and bge-m3 for embeddings.
- bge-m3 produces 1024 dimensions; future pgvector storage must match.
- The framework sample checks local chat and embedding calls. Streaming, tool
  execution and PostgreSQL retrieval still require later integration tests.
- Local checks complement CI. Require both Repository checks and Build and test
  to pass before merging each PR.

## Working agreement

Apply the Definition of Ready and Definition of Done in
[CONTRIBUTING.md](../../CONTRIBUTING.md). Deliver each issue on its own branch,
review the exact staged files, and reference the issue in its PR. After squash
merge, sync main and confirm that GitHub closed the linked issue.

## Sprint closure

Close #11 as completed based on PR #19 and the local chat/embedding evidence.
Merge #12 after its required checks pass, then verify both issue states. Until
then, the delivered foundation is present but the sprint's administrative
closure is still pending. See [the retrospective](sprint-00-retro.md).

## Candidate next work

Refine and estimate these items before selecting a new sprint: tenant identity
and authorization; document upload, parsing and chunking; asynchronous ingestion;
embedding persistence and authorized retrieval; grounded chat with citations and
no-answer behavior. These are proposals, not a committed Sprint 1 backlog.
