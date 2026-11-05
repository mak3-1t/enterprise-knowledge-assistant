# EKA delivery roadmap and reconciliation

Updated: 2026-11-05. Source: original roadmap supplied by the developer and the
Claude handover EKA-handover.md, reconciled with local source and public GitHub API.
The handover's older unknowns do not override later merged implementation evidence.
EKA-n below are original planning IDs, not GitHub issue numbers.

## Original roadmap

Sprint 0: 3–5 days. Sprint 1–6: two weeks each, originally 12 weeks total.
Dates/capacity remain planning assumptions, not a revised delivery commitment.

| Sprint | Epic | Original scope | Verified implementation |
|---|---|---|---|
| 0 | E1 | Monorepo, Compose, CI, ADR, framework spike | Foundation merged; later DoD gaps listed below |
| 1 | E1 | Auth, multi-tenant, document upload | Auth/account isolation implemented; original scope incomplete |
| 2 | E2 | Outbox/Kafka, parse, chunk, embed, retry/DLT, ingestion status | Not implemented |
| 3 | E3 | RAG chat, SSE, citations, conversation, no-answer | Not implemented |
| 4 | E4/E5 | Hybrid RRF, reranking, ACL, bounded agent/tools | Not implemented |
| 5 | E6/E7 | Resilience/fallback, guardrails, Redis cache/rate limit, evaluation | Not implemented |
| 6 | E8 | Performance, observability, K8s, final documentation | Not implemented |

Frontend is a minimal Sprint 3 deliverable; technology is undecided. No new deadline
or reduced MVP is implied by the developer's hypothetical five-day question.

## Sprint 1 original stories vs actual code

| Original ID | Requirement | Evidence and gap |
|---|---|---|
| EKA-1 (3 pts) | Register tenant and first ADMIN | Dev bootstrap exists; public registration not implemented |
| EKA-2 (5 pts) | Login, access/refresh tokens | PR #27 / issue #26: BCrypt, 15-minute RS256 access JWT; refresh deliberately deferred in ADR-004 |
| EKA-3 (5 pts) | ADMIN/EDITOR/VIEWER RBAC | Current DB/Java/bootstrap use ADMIN/MEMBER; requires forward migration and tests |
| EKA-4 (5 pts) | Tenant isolation, RLS or filter | PR #25/#29 implement tenant-scoped JDBC/account HTTP tests; document isolation still unproved |
| EKA-5 (5 pts) | Upload to object storage and list; limits; DB metadata | No implementation; remains Sprint 1 work |
| EKA-6 (2 pts) | Swagger/OpenAPI and health | Health implemented; OpenAPI/Swagger absent |

Total original Sprint 1: 25 points. This is not a remaining-work estimate or measured
velocity. The narrower S1-01..04 auth plan is historical; it did not replace the
original product scope. Member read APIs are useful delivered additions, not a
substitute for registration/upload/three-role RBAC.

## Confirmed current decisions

- Java 21, Spring Boot 4.1.2, modular monolith (ADR-001), auth-owned schema/JDBC.
- Spring AI selected by merged ADR-002; standalone spike evidence exists. Runtime
  model integration is not implemented. bge-m3 is local embedding, dimension 1024;
  production provider/model remains undecided.
- S3 protocol/adapter boundary, development fork in ADR-003. Tested immutable image
  selection is outstanding; Compose currently uses pgsty/minio:latest.
- Local JWT issuer/external RSA keys/current DB identity per ADR-004 is delivered.
  Original refresh/registration requirements remain backlog, not implicit acceptance
  of their permanent removal. Add a new decision record before changing token lifecycle.
- Tenant identity comes from verified JWT + active DB account; no client override.
- 2026-11-05 developer approval: ADMIN manages all documents in own tenant; EDITOR
  uploads and deletes only own uploads; VIEWER reads/downloads. Existing MEMBER
  migrates to VIEWER. All three can list/read/download own-tenant documents initially;
  per-document ACL filtering remains Sprint 4. This records policy, not shipped code.
- ADMIN member-management read routes remain ADMIN-only. New document rules do not
  widen access to auth-owned membership APIs.

## Remaining product decisions

Before upload implementation: allowed formats/size, duplicate behavior, download
transport, bucket/key layout and deletion semantics. Candidate formats from planned
Tika parsing are PDF/DOCX/TXT/MD, not a confirmed Sprint 1 allowlist. Prior PDF/TXT,
10 MiB, ADMIN-only upload and expanded deletion-recovery proposal were not approved
baseline and are superseded by this reconciliation. Do not implement them as facts.

Confirm registration trust/abuse controls and refresh rotation/revocation contract
in the corresponding stories. Do not add a public provisioning endpoint by exposing
the development bootstrap. Keep file storage lifecycle separate from ingestion
UPLOADED/PROCESSING/READY/FAILED; UPLOADED never means embedded/searchable.

## Verification evidence

- Local and remote main: 7bb2c62 (PR #30). Public GitHub API on 2026-11-05 confirms
  PR #25, #27, #29, #30 merged; main CI run 41987353426 success, including Maven,
  Sprint 1 auth demo and framework spike. Prior failure 41862427109 preceded key URI fix.
- Developer's fresh Maven verify on 2026-11-05: BUILD SUCCESS, all eight reactor
  entries successful after Spotless apply. Stored test reports previously total 46.
- Auth demo: 13 checks pass; this proves account flows, not original Sprint 1 completion.
- Audit is not a penetration test. No dependency-vulnerability clean claim is made.

## GitHub delivery hygiene — observed, not changed

- Issue #28 remains open; merged PR #29 body has incomplete Closes #.
- PR #30 has no dedicated linked story; Project UI shows Done, no milestone.
- Ruleset protect-main is active, only Repository checks is required; Build and test
  must be made required to match the written workflow. Other active rules: PR,
  linear history, no deletion/force push. Current API did not establish bypass actors.
- Seven milestones exist; Sprint 0 has 9 closed items, Sprint 1–6 have none assigned.
- No tags/releases, although original deliverables call for v0.1.0 onward. Do not
  tag v0.1.0 as complete before the reconciled Sprint 1 criteria are satisfied.
- Open PR #4 is Dependabot checkout upgrade. Treat separately from product scope.
- Project fields/workflows and private security alerts/settings are not fully audited.
  CLI auth is invalid; public API data remains available. Browser inspection was stopped.

## Original Definition of Done gaps

Original DoD includes business-logic coverage >=70%, vulnerability scanning,
OpenAPI, integration tests, logs/metrics and Compose execution. Current CI produces
JaCoCo reports but has no coverage gate, no vulnerability scan, and no app Compose
service. Turn these into explicit ready stories; do not claim reports alone enforce
coverage, or infrastructure-only Compose runs the whole product.

Final acceptance also requires chunk-size comparison, before/after reranking
quality evaluation, before/after p95/throughput benchmark, tested provider fallback,
one-command full Compose startup and documented architecture/demo evidence.

## Delivery order

1. Merge this documentation reconciliation after review and CI; no runtime change.
2. Implement three-role RBAC via a forward migration; retain auth and isolation tests.
3. Complete registration/refresh contracts and their individual stories.
4. Implement upload/list metadata + private storage with role/tenant tests, then
   OpenAPI and the remaining Sprint 1 DoD gates. Split stories <=8 points.
5. Complete original Sprint 1 acceptance, then Sprint 2 Outbox -> parser -> chunking
   -> embeddings, with idempotency/retry/DLT and status tests, per linked backlog.

The original IDs are not GitHub issue numbers. Create actual issues, map their IDs,
use one issue/branch/PR per story and complete Closes references before merging.
