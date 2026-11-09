# Sprint 3 local demo

Open http://localhost:8080 after starting the Spring Boot JAR. The web UI is served by
the same process as the API. It uses an in-memory bearer token and requires a new login
after refreshing the page. The saved conversation remains in PostgreSQL. This demo UI
opens the current conversation only and does not provide a conversation browser.

## Start locally

1. Follow [local auth](local-auth.md) and [database setup](auth-persistence.md) to set
   the PostgreSQL credentials and signing key locations in the app terminal. Use the
   development provisioning settings for a local account, or explicitly enable and use
   [tenant registration](tenant-registration.md) for a second tenant.
2. Start the existing Compose services and ClamAV. Create a private `eka-documents`
   bucket through the local S3 console, or set `EKA_UPLOAD_BUCKET` to an existing private
   bucket. Set `EKA_UPLOAD_ACCESS_KEY` and `EKA_UPLOAD_SECRET_KEY` to credentials for it.
3. Start local Ollama with the models in [local model setup](local-llm.md). Use the
   installed bge-m3 digest as the embedding model version. If existing documents were
   indexed under another version label, keep that label or index fresh uploads.

```powershell
docker compose --env-file .env -f deploy/docker/docker-compose.yml --profile malware up -d --wait --wait-timeout 600
$env:EKA_UPLOAD_ENABLED = "true"
$env:EKA_UPLOAD_SCAN_ENABLED = "true"
$env:EKA_INGESTION_KAFKA_ENABLED = "true"
$env:EKA_KAFKA_CREATE_TOPIC = "true"
$env:EKA_DOCUMENT_PARSER_ENABLED = "true"
$env:EKA_INGESTION_EMBEDDING_ENABLED = "true"
$env:EKA_INGESTION_EMBEDDING_MODEL_VERSION = "<local-model-digest-or-existing-label>"
$env:EKA_CHAT_ENABLED = "true"
$env:EKA_CHAT_MIN_SIMILARITY = "0.45"
.\mvnw.cmd -B -DskipTests package
java -jar app/target/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --server.address=127.0.0.1
```

The similarity threshold accepts 0 through 1. The default 0.45 is a starting
configuration, not a measured optimum for all corpora. The service filters the
retrieved chunks before prompting. If none qualify, it returns NO_EVIDENCE and does
not call the generation model.

## Exercise two tenants

Upload only the files in each tenant's corresponding directory under
[the synthetic corpus](fixtures/sprint-03). Click Làm mới until the uploads are READY.
Use the separate [expected answers](fixtures/sprint-03/expected.md) for manual
checking. Compare the conflicting policy answers and their filenames between tenants.
Try an unsupported question, a canceled stream and a saved conversation. Keep the
expected answers file out of both tenants' document collections.

## Automated verification

The app test uses real PostgreSQL, Kafka and S3 with deterministic model and malware
responses. It checks that the UI assets are served and exercises the API for upload,
tenant-scoped answers, SSE, citations, saved turns and an off-topic vector.

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/sprint-03-acceptance-verify.log
```

## Browser verification

On 2026-11-09, Alpha and Beta both uploaded their fixtures to local MinIO and
reached READY. With local Ollama, the same remote-work question returned two days
for Alpha and three days for Beta, each citing its own fixture. The Alpha lunch
allowance question displayed the no-evidence message without citations after the
decline fix. Multiple independent uploads of the same Beta file appeared as
separate sources, in line with the upload policy.

For a browser upload failure, confirm the private bucket exists before retrying.
The signed POST uses no-cors with the browser's default redirect behavior, and the
completion API verifies the object before acknowledging the upload. The chat
prompt asks the provider to return NO_EVIDENCE when its sources cannot answer.
The server treats that response as a decline even if a citation marker follows it.
