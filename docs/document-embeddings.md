# Document embeddings

After parsing commits private source sections, the opt-in embedding worker polls PostgreSQL
for the current source generation of PROCESSING documents. Claims are durable and use a
five-minute lease. The worker renews the lease before each provider call. Transient failures
retry after 5, 10 and 20 seconds. Four failed or expired attempts end in FAILED. An empty
source or more than 4096 chunks fails without making a provider call.

| Property | Default |
|---|---|
| `eka.ingestion.embedding.enabled` | false |
| `eka.ingestion.embedding.base-url` | `http://localhost:11434` |
| `eka.ingestion.embedding.model` | `bge-m3:latest` |
| `eka.ingestion.embedding.model-version` | Required when enabled |
| `eka.ingestion.embedding.batch-size` | 8, range 1–16 |

The model version is a deployment label. Use the installed model digest and keep the
configured model stable across workers and readers. The adapter verifies the returned model
name and vector shape. It does not verify the digest on every request. It sends document
text to the configured provider, so enable it only for an approved endpoint.

Flyway V9 requires pgvector to be installed on PostgreSQL. The development Compose image
already includes it. On a managed database, provision the extension before running Flyway
if the migration role cannot create extensions.

Each vector row points to a tenant-owned source section and stores its ordinal, exact
UTF-16 offsets, model and model version. Page and text can be resolved from the source.
All vectors and the READY status commit in one transaction. Failed or expired attempts
discard in-memory results and recompute on retry. Soft delete removes source sections,
which cascades to the vectors immediately. Restore goes through scan and parse again.
Generation and lease checks reject stale results before publication.

The primary key supports tenant/document/generation lookup and rejects duplicate ordinals.
Similarity queries and their index will be introduced with retrieval. That reader must
filter by tenant, current source generation, READY and compatible model version and apply
document access rules. Changing the deployment model affects newly processed documents.
Existing vectors keep their model version until the documents are reprocessed. A bulk
reindex workflow and Kafka DLT/replay remain separate work.

The local packaged adapter smoke used two synthetic strings with `bge-m3:latest` and
returned two 1024-dimensional vectors. CI uses deterministic vectors in the pgvector
persistence test. Run the full developer verification with Docker Desktop running:

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/document-embedding-verify.log
```
