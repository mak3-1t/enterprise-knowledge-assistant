# Sprint 2 acceptance demo

The application integration test starts isolated PostgreSQL with pgvector, Kafka and
S3-compatible storage. It uses two authenticated tenants and a synthetic TXT file.
No cloud key or local model is needed. The embedding endpoint returns a deterministic
1024-dimensional vector and the malware scanner returns a clean verdict. Actual Ollama
and ClamAV are verified separately. The scan worker, format inspector, child Tika parser,
chunker, Spring AI adapter and embedding publication use the production wiring.

The test checks these behaviors in order:

1. Create an upload intent and submit a signed multipart POST to the private object store.
2. Seal the original through the completion endpoint and observe UPLOADED.
3. Wait for the model request. Confirm the exact synthetic text reaches the provider,
   truncation is disabled, status is PROCESSING, download is blocked and no vectors exist.
4. Release the model response. Wait for READY, verify one vector was published and
   download the original as the owner.
5. Compare the foreign tenant's detail response with a missing document. Confirm that
   the foreign tenant cannot download it.
6. Delete the document. Confirm its vector is gone and download returns 404.

The full verifier also runs independent scanner, retry, dead-letter and operator replay tests.
From a Windows terminal with Docker Desktop running:

```powershell
& .\scripts\verify-document-scan.ps1 -LogPath target/sprint-02-acceptance-verify.log
```

The acceptance case runs in the ordinary Maven CI suite. Record the actual test result
and CI link before closing the sprint or tagging a release.

## Developer verification

On 2026-11-07 at 23:46:59 +07:00, Maven reported BUILD SUCCESS with 198 tests passed,
zero failures, zero errors and zero skips. The application acceptance case passed in
19.38 seconds. The same full run also passed the independent live ClamAV check.
