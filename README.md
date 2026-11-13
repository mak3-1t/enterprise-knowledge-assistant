# Enterprise Knowledge Assistant

![CI](https://github.com/mak3-1t/enterprise-knowledge-assistant/actions/workflows/ci.yml/badge.svg)

Multi-tenant RAG platform for enterprise documents, built with Java 21, Spring Boot and Spring AI.

> Status: Sprint 2 ingestion is released as v0.2.0. Sprint 3 RAG chat and the local browser demo are released as v0.3.0. Sprint 4 hybrid retrieval is in progress.

## What it does
Employees ask questions in natural language. The system answers with source citations,
respects per-user permissions, and keeps each tenant's data isolated.

## Planned tech stack
Java 21, Spring Boot 4.1, Spring AI, PostgreSQL + pgvector, Redis, Kafka, S3-compatible object storage, Docker, Kubernetes.

## Quick start

Requirements: Java 21, Docker Desktop. The app also needs PostgreSQL credentials in its process environment; see [auth persistence setup](docs/auth-persistence.md).

```bash
cp .env.example .env        # PowerShell: Copy-Item .env.example .env
docker compose --env-file .env -f deploy/docker/docker-compose.yml up -d
```

Starts PostgreSQL + pgvector, Redis, Kafka (KRaft) and an S3-compatible object store.
All ports are bound to 127.0.0.1. Check health with `docker compose ... ps`.
## Documentation
- Document metadata foundation: [docs/document-metadata.md](docs/document-metadata.md)
- Refresh tokens and logout: [docs/refresh-sessions.md](docs/refresh-sessions.md)
- Opt-in tenant registration: [docs/tenant-registration.md](docs/tenant-registration.md)
- Three-role migration: [docs/auth-roles.md](docs/auth-roles.md)
- Original roadmap and delivery gaps: [docs/roadmap.md](docs/roadmap.md)
- Sprint 1 remaining scope: [completion backlog](docs/sprint-notes/sprint-01-completion.md)
- Sprint 2 plan and backlog: [planning](docs/sprint-notes/sprint-02-planning.md), [backlog](docs/sprint-notes/sprint-02-backlog.md)
- Sprint 1 acceptance demo: [docs/sprint-01-demo.md](docs/sprint-01-demo.md)
- Tenant identity and member APIs: [docs/tenant-member-api.md](docs/tenant-member-api.md)
- Local login and JWT: [docs/local-auth.md](docs/local-auth.md)
- Auth database setup and integration tests: [docs/auth-persistence.md](docs/auth-persistence.md)
- Architecture decisions: [docs/adr](docs/adr)
- Sprint notes: [docs/sprint-notes](docs/sprint-notes)

## License
Apache License 2.0

- [Document upload](docs/document-upload.md) and [content scanning](docs/document-scan.md) (working implementation; full verification status in the documents).

- [Document Outbox to Kafka](docs/document-outbox-kafka.md) (Sprint 2 relay; verification status in the document).

- [Durable Tika text parsing](docs/document-text-parser.md) (verification status and remaining scope in the document).

- [Run the Sprint 3 web demo locally](docs/sprint-03-demo.md).
- [Sprint 4 plan and backlog](docs/sprint-notes/sprint-04-planning.md).
- [Tenant-scoped lexical retrieval](docs/lexical-document-search.md).
