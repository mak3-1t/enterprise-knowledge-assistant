# Sprint 0 retrospective

Date: 2026-11-02. Technical review based on merged changes, development logs and
GitHub state. This records observed outcomes and process improvements, not
personal feedback or an invented retrospective meeting.

## Outcome

The foundation work in #5 through #11 is merged, including the Maven modules,
local infrastructure, CI and coverage, Java style gates, architecture decisions
and local-model framework spike. #11 still needs its issue closed despite the
merged implementation. #12 contains these closing documents and is pending merge.
The application remains an early skeleton; tenant-aware RAG is not implemented.

## What worked

- Establishing Maven CI before feature development gave later PRs a repeatable
  build and test gate. Required check names stayed stable.
- Local-model measurements exposed excessive reasoning in the initial Qwen
  model. Switching to the Instruct variant made the greeting smoke test usable.
- Keeping the framework spike standalone avoided adding both frameworks to the
  application runtime. Its build runs in CI without a model server.
- Spotless and Checkstyle now agree with four-space Java indentation and reject
  intentionally malformed source. Mockito startup-agent configuration preserves
  JaCoCo while avoiding the observed Windows dynamic-attachment stall.
- ADRs capture the monolith, framework and local object-storage decisions.

## Friction and causes

| Observation | Cause | Improvement |
|---|---|---|
| Git commands failed in the project parent directory | The shell was outside the actual repository | Check repository root and branch before changes |
| Maven target output was committed during skeleton setup | Ignore rule was missing and folder summaries hid generated files | Keep target ignored; inspect exact staged paths with -uall |
| #11 stayed open after merge and branch deletion | PR #19 contained Closes # without an issue number | Validate the complete issue reference and check issue state after merge |
| README mentioned Boot 3 despite the Boot 4 skeleton | Documentation drift | Compare documentation against the parent POM when recording decisions |
| Framework integration version copied from another artifact did not resolve | Published versions differ between integrations | Verify each artifact in Maven Central and pin it |
| Assistant work initially combined several issues and created commits prematurely | The established per-issue handoff was not followed | Prepare one issue at a time; developer reviews, commits, pushes and merges |

## Actions for the next sprint

| Action | Owner | When / evidence |
|---|---|---|
| Close #11 based on merged PR #19 | Developer | Sprint closure; GitHub issue shows closed |
| Review and merge #12 with green checks | Developer | Sprint closure; documents present on main |
| Use one issue per branch and PR | Developer and coding assistant | Every story; PR diff contains only that scope |
| Inspect staged paths and use a complete Closes reference | Developer | Before every commit and PR; no target or .env tracked |
| Refine testable tenant/ACL, citations and no-answer criteria | Developer | Before accepting RAG feature stories into the next sprint |
| Exercise streaming, tool contracts and pgvector in integration tests | Developer | In the corresponding implementation stories; the spike is not proof of these behaviors |

## Limits of the evidence

A single local-model sample is not a framework performance benchmark or a RAG
quality evaluation. No velocity, productivity or personal satisfaction score is
inferred from these commits. Add the developer's own feedback separately if useful.
