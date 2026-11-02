# Local framework spike (#10)

Standalone Java 21 project; deliberately outside the application reactor.
Prerequisites: Maven, running Ollama, and the two models in docs/local-llm.md.
No PostgreSQL or cloud credentials are needed.

From the repository root (PowerShell):

```powershell
./mvnw.cmd -B -f spikes/ai-framework/pom.xml compile exec:java '-Dexec.mainClass=com.eka.spike.LocalModelSmoke'
```

The sample fails on empty replies or an embedding dimension other than 1024.
It sends only synthetic prompts to localhost:11434. It does not pull models.
The first call may take longer while loading the model. Printed elapsed times are
smoke-test diagnostics, not a comparative benchmark. See ADR-002 for the decision
and for capabilities that still require integration tests.

Compile without calling Ollama:

```powershell
./mvnw.cmd -B -f spikes/ai-framework/pom.xml verify
```

CI runs verify without exec:java, so it builds and checks Java formatting without requiring Ollama.
