# Optional document reranking

EKA can optionally rerank the bounded top ten candidates produced by tenant-scoped
hybrid retrieval. The adapter uses the Text Embeddings Inference `POST /rerank`
contract. It sends only the authenticated tenant's retrieved evidence, limits query
text to 2,000 characters, candidate count to ten, and each passage to 6,000
characters. The response must contain one finite score for every candidate index;
invalid responses are rejected.

Reranking is **off by default**. Enable it explicitly with
`EKA_RERANKING_ENABLED=true` and set `EKA_RERANKER_ENDPOINT` to an approved local or
private TEI service. Evidence text is sent to that configured endpoint, so do not
point it at an unapproved external service. The HTTP client uses a 3 second connect
timeout and 15 second response timeout.

If the reranker fails or returns an invalid ordering, the request falls back to the
original RRF ranking. Low-cardinality metrics record request outcome, latency, and
candidate count; logs do not contain queries or document text. This optional stage
does not replace tenant filters in retrieval. Future document ACL filtering must run
before this stage so unauthorized evidence is never sent to the reranker.

## Local smoke setup

Text Embeddings Inference exposes reranker models through `/rerank`; see the
[official TEI quick tour](https://huggingface.co/docs/text-embeddings-inference/quick_tour#re-rankers)
and its [supported model list](https://huggingface.co/docs/text-embeddings-inference/en/supported_models).
On a CPU development machine, run the multilingual GTE reranker on port 8081:

```powershell
docker volume create eka-reranker-cache
docker run --rm -p 8081:80 -v eka-reranker-cache:/data --pull always `
  ghcr.io/huggingface/text-embeddings-inference:cpu-1.9 `
  --model-id Alibaba-NLP/gte-multilingual-reranker-base --port 80
```

The first start downloads the model into the named volume. Keep the container running
while using EKA. Then verify the endpoint:

```powershell
$body = @{ query = 'remote work policy'; texts = @('Employees may work remotely up to two days each week.', 'The cafeteria is open on weekdays.'); truncate = $true } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/rerank -ContentType 'application/json' -Body $body
```

Then start EKA with:

```powershell
$env:EKA_RERANKING_ENABLED = 'true'
$env:EKA_RERANKER_ENDPOINT = 'http://localhost:8081'
```

The automated tests verify request shape, index/score validation, bounded inputs,
reranking order, and failure fallback. These tests use a deterministic mock endpoint;
they do not claim that a specific model improves relevance. Before enabling this in
a shared environment, run the same labeled corpus with reranking disabled and enabled,
record MRR/nDCG and p50/p95 latency, identify the model/version, and review data
handling for the configured endpoint.
