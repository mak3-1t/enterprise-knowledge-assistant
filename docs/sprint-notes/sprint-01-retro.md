# Sprint 1 auth-subset technical retrospective

> Scope correction, 2026-11-05: this retrospective covers delivered auth/account
> work. It does not close original Sprint 1, which includes upload and other gaps.
> See [roadmap](../roadmap.md) and [completion backlog](sprint-01-completion.md).

Date: 2026-11-04. Based on merged implementation, developer-supplied build output
and the live acceptance demo. This is a technical review, not a retrospective meeting.

## Outcome

The authenticated tenant-scoped backend goal is demonstrated locally. #24 delivers
Flyway tenant/account tables and PostgreSQL-backed persistence tests; #26 delivers
local login and validated short-lived JWTs; #28 delivers identity and tenant ADMIN
member reads with role enforcement. Those stories are merged. Local main was clean
and synchronized at a5d1532 before S1-04 work started.

The developer ran the S1-04 packaged-application demo successfully: 13 checks passed.
Two ADMIN identities belong to distinct tenants; MEMBER identity belongs to A;
both lists and own member reads respect scope; pagination is bounded; both foreign
ID directions match missing-ID 404; MEMBER gets 403; absent/malformed tokens get
401. Repeated bootstrap preserves identity/account count, and Flyway V1 runs once.

The prior full Maven run reported 46 tests (27 auth, 19 app) passing without skips,
including style gates. S1-04 adds the demo to CI; its own CI and merge are still
pending. Sprint 1 is not administratively closed until those delivery gates pass.

## What worked

- Schema constraints and real PostgreSQL tests established the persistence boundary
  before authentication and member HTTP behavior were added.
- The service and HTTP security layers both enforce role checks; SQL includes tenant
  scope before listing, pagination or member lookup.
- The packaged-app demo verifies configuration, migration and bootstrap together,
  complementing controlled-clock and concurrent-request automated tests.
- Disposable PostgreSQL, ephemeral keys and in-memory random credentials make the
  acceptance run independent of existing developer accounts and persistent data.

## Friction and follow-up

| Observation | Action |
|---|---|
| Commands sometimes ran from the repository parent | Start instructions with the exact repository directory |
| Agent sandbox could not access Docker's named pipe | Separate agent static verification from developer live-run evidence; run demo in CI |
| PowerShell 7 executable was not on terminal PATH | Document PowerShell 7 requirement; use its absolute path locally or install it on PATH |
| Planning text retained its original proposed status after implementation | Keep the baseline, append explicit issue mapping and delivery status |

## Remaining limits

This proves account-data isolation, not document ACLs or PostgreSQL RLS. Local JWT
issuance is not enterprise SSO. Refresh/revocation, registration, password reset,
production key management and rate limiting remain future work. Document ingestion,
chunking, embedding, retrieval, citations, streaming and agents are not delivered.
No velocity, elapsed engineering time or model-quality score is inferred.

## Next actions

1. Review and merge S1-04 only after Repository checks and Build and test are green;
   use an actual GitHub issue number in its PR, then synchronize main.
2. Refine Sprint 2 scope before coding: tenant-owned document upload/storage,
   processing lifecycle and authorization need explicit acceptance criteria.
3. Carry the tenant boundary into document reads/writes and negative tests; account
   API isolation alone is insufficient evidence for future document access.
4. Define citation and no-answer criteria before accepting RAG quality work.

## Delivery update — 2026-11-05

PR #30 is merged in main at 7bb2c62, including the Linux file-URI fix for demo keys.
The developer confirmed completion and supplied synchronized clean main output.
This supersedes the pending-merge wording in the original retrospective above.
Sprint 2 planning is tracked in sprint-02-planning.md and sprint-02-backlog.md.
