# Enterprise Knowledge Assistant

![CI](https://github.com/mak3-1t/enterprise-knowledge-assistant/actions/workflows/ci.yml/badge.svg)

Multi-tenant RAG platform for enterprise documents, built with Java 21, Spring Boot and Spring AI.

> Status: Sprint 1 authentication and tenant APIs implemented; acceptance demo in progress.

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
- Sprint 1 acceptance demo: [docs/sprint-01-demo.md](docs/sprint-01-demo.md)
- Tenant identity and member APIs: [docs/tenant-member-api.md](docs/tenant-member-api.md)
- Local login and JWT: [docs/local-auth.md](docs/local-auth.md)
- Auth database setup and integration tests: [docs/auth-persistence.md](docs/auth-persistence.md)
- Architecture decisions: [docs/adr](docs/adr)
- Sprint notes: [docs/sprint-notes](docs/sprint-notes)

## License
Apache License 2.0
