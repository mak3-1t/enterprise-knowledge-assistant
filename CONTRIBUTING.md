# Contributing

## Workflow
1. Pick a story from the GitHub Project board and move it to In Progress.
2. Create a branch: `feat/12-short-description`.
3. Commit using Conventional Commits: `feat(ingestion): add tika parser`.
4. Open a Pull Request that links the issue (`Closes #12`).
5. Wait for CI to pass, self-review the diff, then squash merge.

## Definition of Done
- Unit tests and integration tests pass
- CI is green
- No secrets in code
- OpenAPI, README or ADR updated when needed
- Logs and metrics added for new flows
