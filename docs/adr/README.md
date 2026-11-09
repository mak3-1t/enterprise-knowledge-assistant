# Architecture Decision Records

| ADR | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith first, microservices later | Accepted |
| [0002](0002-ai-framework.md) | Use Spring AI for EKA | Accepted |
| [0003](0003-s3-compatible-object-storage.md) | S3-compatible object storage instead of upstream MinIO | Accepted (dev) |
| [0004](0004-local-authentication.md) | Local authentication and tenant identity | Accepted (MVP) |
| [0005](0005-three-role-rbac.md) | Restore ADMIN / EDITOR / VIEWER roles | Accepted policy; implementation pending merge |
| [0006](0006-tenant-registration.md) | Explicit opt-in tenant registration | Accepted policy; implementation pending merge |
| [0007](0007-refresh-sessions.md) | Rotating refresh tokens and revocable sessions | Accepted policy; implementation pending merge |
| [0008](0008-document-metadata.md) | Tenant-owned document metadata | Locally verified; merge pending |
| [0009](0009-durable-document-upload.md) | Durable document upload and recovery | Locally verified; merge pending |
| [0010](0010-document-content-scan.md) | Durable content and malware scan | Locally verified; merge pending |
| [0011](0011-document-outbox-kafka.md) | At-least-once Outbox relay to Kafka | Locally verified; CI/merge pending |
| [0012](0012-durable-text-parsing.md) | Durable inbox and parsed source generations | Implementation pending integration verification/merge |
| [0013](0013-chat-provider.md) | Isolate chat generation behind LlmProvider | Accepted for Sprint 3 |
