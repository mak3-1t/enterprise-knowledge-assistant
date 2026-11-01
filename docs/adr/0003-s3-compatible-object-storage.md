# ADR-003: S3-compatible object storage instead of upstream MinIO

- Status: Accepted (dev environment), to be revisited
- Date: 2026-11-01

## Context
The initial plan used MinIO for storing uploaded documents. Public sources report that
upstream `minio/minio` stopped publishing community Docker images in October 2025 and
that the repository was archived in April 2026. A new project should not depend on it.

## Decision
- The application talks to storage only through the S3 API (AWS SDK), never through a
  vendor-specific client. The storage product behind it is an infrastructure detail.
- For local development, use the community fork `pgsty/minio` in Docker Compose.
- Credentials come from `.env`; the endpoint and bucket are configuration.

## Consequences
- Switching to another S3-compatible store (SeaweedFS, RustFS, Garage, or a cloud S3)
  should require configuration changes only.
- The fork's image tag is not pinned yet (`latest`). TODO: pin a tested version.
- The fork is community-maintained; its long-term support is unverified.

## Alternatives considered
- SeaweedFS, RustFS, Garage: S3-compatible and mentioned as active alternatives. Not
  evaluated hands-on yet.
- Storing files in PostgreSQL: simple but poor fit for large documents.