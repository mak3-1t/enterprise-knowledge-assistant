# EKA delivery roadmap and reconciliation

Updated: 2026-11-13. Source: original roadmap supplied by the developer and the
Claude handover EKA-handover.md, reconciled with local source and public GitHub API.
The handover's older unknowns do not override later merged implementation evidence.
EKA-n below are original planning IDs, not GitHub issue numbers.

## Original roadmap

Sprint 0: 3–5 days. Sprint 1–6: two weeks each, originally 12 weeks total.
Dates/capacity remain planning assumptions, not a revised delivery commitment.

| Sprint | Epic | Original scope | Verified implementation |
|---|---|---|---|
| 0 | E1 | Monorepo, Compose, CI, ADR, framework spike | Foundation and framework decision delivered; remaining DoD gaps listed below |
| 1 | E1 | Auth, multi-tenant, document upload | Delivered across auth and document lifecycle stories; see merged release history |
| 2 | E2 | Outbox/Kafka, parse, chunk, embed, retry/DLT, ingestion status | Delivered and released as v0.2.0; Sprint 2 acceptance recorded in sprint notes |
| 3 | E3 | RAG chat, SSE, citations, conversation, no-answer | Delivered and released as v0.3.0; two-tenant browser acceptance recorded in sprint-03-demo.md |
| 4 | E4/E5 | Hybrid RRF, reranking, ACL, bounded agent/tools | In progress; EKA-19/20 verified; EKA-21 opt-in TEI reranking implemented, model-on-corpus quality/latency evaluation pending; EKA-22 role ACL implemented; EKA-23 remains |
| 5 | E6/E7 | Resilience/fallback, guardrails, Redis cache/rate limit, evaluation | Not started |
| 6 | E8 | Performance, observability, K8s, final documentation | Not started |

Frontend is a minimal same-origin UI served by the Spring Boot app. No separate
frontend framework or new deadline is implied by the developer's hypothetical
five-day question.

## Sprint 1 original stories vs actual code

| Original ID | Requirement | Evidence and gap |
|---|---|---|
| EKA-1 (3 pts) | Register tenant and first ADMIN | PR #35 / issue #34: opt-in registration creates tenant + ADMIN atomically |
| EKA-2 (5 pts) | Login, access/refresh tokens | PR #27/#37: BCrypt, access JWT and seven-day rotating refresh sessions with immediate logout |
| EKA-3 (5 pts) | ADMIN/EDITOR/VIEWER RBAC | PR #33 / issue #32: ADMIN/EDITOR/VIEWER and MEMBER -> VIEWER migration |
| EKA-4 (5 pts) | Tenant isolation, RLS or filter | Auth/account and document routes use tenant-scoped data access; Sprint 3 cross-tenant browser acceptance is recorded in `sprint-03-demo.md` |
| EKA-5 (5 pts) | Upload to object storage and list; limits; DB metadata | Delivered across document metadata, private object storage, upload lifecycle and ingestion stories; released by v0.2.0 |
| EKA-6 (2 pts) | Swagger/OpenAPI and health | Health implemented; OpenAPI/Swagger remains an explicit documentation/API discoverability gap |

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
  Registration and refresh are now delivered by PR #35/#37 and ADR-006/007; signup
  is opt-in, sessions last seven days absolutely, and logout revokes the session.
- Tenant identity comes from verified JWT + active DB account; no client override.
- 2026-11-05 developer approval: ADMIN manages all documents in own tenant; EDITOR
  uploads and deletes only own uploads; VIEWER reads/downloads. Existing MEMBER
  migrates to VIEWER. All three can list/read/download own-tenant documents initially;
  per-document ACL filtering remains Sprint 4. This records policy, not shipped code.
- ADMIN member-management read routes remain ADMIN-only. New document rules do not
  widen access to auth-owned membership APIs.

## Remaining product decisions

Upload policy approved 2026-11-06: PDF/DOCX/TXT/MD, one file/request, <=20 MiB;
each repeat creates a separate document. The document flow, private storage, scan,
parse, chunk, embedding and recovery paths have since been implemented and accepted
through Sprint 2. Keep subsequent authorization and retention behavior aligned with
the documented tenant policy. Earlier proposals for PDF/TXT only, 10 MiB, ADMIN-only
upload and expanded deletion recovery were not approved baseline.

Registration trust limits and refresh rotation/revocation are documented in
[tenant-registration.md](tenant-registration.md) and [refresh-sessions.md](refresh-sessions.md). Do not add a public provisioning endpoint by exposing
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

## Current delivery order

Sprint 1–3 implementation has advanced beyond the earlier audit snapshots above.
Those historical details explain the initial reconciliation, not current open work.
Sprint 4's active sequence and acceptance criteria are maintained in
[Sprint 4 planning](sprint-notes/sprint-04-planning.md). The remaining cross-sprint
gaps are OpenAPI, enforced coverage threshold, vulnerability scanning, measured
retrieval/reranking quality, latency benchmarks, provider fallback, and production
deployment work; track them in their roadmap sprint rather than treating them as
completed because earlier stories passed.

Planning IDs are not GitHub issue numbers. Create actual issues, map their IDs, use
one issue/branch/PR per story and complete Closes references before merging.

## Content scan working-branch update — 2026-11-06

V4/V5 upload baseline passed 114 developer-run tests. V6 adds a local durable scan
worker and format/ClamAV gates, pending integration verification; see
[content scan](document-scan.md). This introduces the scan prerequisite early without
claiming Kafka, parse/chunk/embed, full OS sandbox or READY publication is delivered.

### Verified checkpoint — 2026-11-06

The full upload/content-scan verification passed 142 tests (zero failures/errors/skips),
including live ClamAV, PostgreSQL/S3 and HTTP suites. BUILD SUCCESS at 16:40:25 +07:00.
See [verification evidence](document-scan.md). Merge/CI and the remaining parse/chunk/embed/READY/isolation scope are pending.

## Current roadmap checkpoint — 2026-11-13

Sprint 3 is closed at release v0.3.0. Sprint 4 planning and delivery order are in
[Sprint 4 planning](sprint-notes/sprint-04-planning.md). EKA-19's tenant-scoped
PostgreSQL lexical search and EKA-20's labeled RRF regression test passed. EKA-21
adds opt-in TEI cross-encoder reranking with bounded payloads, response validation,
timeouts, fallback to RRF, and telemetry. It remains disabled by default until a
model/version is evaluated on the labeled corpus for relevance and latency. Do not
claim corpus-wide quality gains from the single synthetic regression case. EKA-22
now defaults documents to tenant-wide read access and lets ADMIN restrict individual
documents to EDITOR and/or VIEWER roles. Role filtering applies to document list,
detail, download, vector search, and lexical search before RRF/reranking. See
[document role ACLs](document-acl.md). EKA-23 bounded tools remain next.
