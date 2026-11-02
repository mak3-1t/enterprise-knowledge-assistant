# Contributing

## Workflow
1. Pick a story from the GitHub Project board and move it to In Progress.
2. Create a branch: `feat/12-short-description`.
3. Commit using Conventional Commits: `feat(ingestion): add tika parser`.
4. Open a Pull Request that links the issue (`Closes #12`).
5. Wait for CI to pass, self-review the diff, then squash merge.

## Definition of Ready
- User value, scope and acceptance criteria are clear and testable.
- Dependencies, required access and data are identified; blockers are resolved.
- The story is estimated and small enough for one sprint.
- Tenant isolation, permissions and other security requirements are explicit when relevant.
- Verification steps are identified, including integration dependencies when needed.

## Definition of Done
- Unit tests and integration tests pass
- CI is green, including Repository checks and Build and test
- No secrets in code
- OpenAPI, README or ADR updated when needed
- Logs and metrics added for new flows
- Acceptance criteria are verified; any non-applicable checks are explained in the PR
- Changes are reviewed and merged; the linked issue is closed and local main is synchronized

## Java style checks
See [Java formatting and static checks](docs/code-style.md) for the rules,
automatic formatting, verification commands and test-agent configuration.
