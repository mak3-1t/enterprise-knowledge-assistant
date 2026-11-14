# Bounded agent tool calling (EKA-23)

The authenticated `POST /api/agent` endpoint lets the configured Ollama model call one
server-provided tool per turn: `search_documents`. The tool only searches indexed,
accessible documents through the existing tenant and document-role-aware retrieval API.
The model cannot provide a tenant ID, select a different tool, or perform a write action.

## Limits and safety

- Endpoint is available when `eka.chat.enabled=true`; requests still require a valid JWT.
- User question: 1–2,000 characters. Tool query: 1–500 characters and at most 2 KiB JSON.
- One tool call per turn, at most five search results, up to 6 KiB tool output.
- Model output is limited to 256 generated tokens and 8,000 characters.
- Overall deadline defaults to 60 seconds and can be set with `eka.agent.deadline-seconds`
  (1–120). Each Ollama HTTP call has a 25-second read timeout.
- Only explicitly attached callbacks are executable; resolver fallback is disabled.
  Tool failures are returned as a generic `AGENT_UNAVAILABLE` response.
- Audit logs and `eka.agent.tool.calls` metrics record tool name, outcome, and duration;
  they do not record questions, document text, credentials, or tokens.
- Retrieval enforces the authenticated tenant, current document generation, READY state,
  soft-delete state, and EKA-22 reader-role ACL before evidence reaches the model.

## Verify

Run unit tests and style checks:

    ./mvnw -B -pl agent -am -Dtest=BoundedAgentServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
    ./mvnw -B -pl agent spotless:check checkstyle:check

With the app and Ollama running, authenticate and send a JSON request. This live smoke
is required in addition to the mock-based tests because support for Ollama tool calling
depends on the selected model:

    POST /api/agent
    Authorization: Bearer <access-token>
    Content-Type: application/json

    {"question":"What does our policy say about remote work?"}

A successful evidence-backed response contains `status: ANSWERED` and validated citation
IDs. Missing evidence returns `NO_EVIDENCE`. Provider, timeout, malformed-tool, or citation
failures return a generic 503 response. Record the model name and whether it completed a
tool call when running the live smoke; do not include tokens or document text in notes.
