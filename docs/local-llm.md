# Local LLM setup (Ollama)

Used for development and the framework spike. Local models are **not** representative
of cloud-model quality, so evaluation results must always state which model produced them.

## Installed models

| Role | Model | Size on disk | Notes |
|---|---|---|---|
| Chat | `qwen3:4b-instruct-2507-q4_K_M` | 2.5 GB | Non-thinking variant of Qwen3 4B |
| Embedding | `bge-m3` | 1.2 GB | Multilingual, 1024-dimensional vectors |

## Measured on the dev machine
Machine: 32 GB RAM, AMD Radeon 780M integrated GPU. Ollama 0.40.13.

- Embedding dimension: **1024**. The pgvector column must be `vector(1024)`.
  Changing the embedding model later means re-embedding every document.
- Processor: **100% CPU** (`ollama ps`); the integrated GPU is not used. Context 4096.
- Chat greeting prompt ("say hello in Vietnamese in one sentence"):
  5 output tokens, 3.9 s total, 19.7 tokens/s.

## Why not `qwen3:4b`
The first chat model tried was `qwen3:4b`. For the same prompt it generated 2286 tokens
(194.7 s) although the visible answer was 8 characters. The reasoning went to a separate
`message.thinking` field (9246 characters). `think=false` and `/no_think` did not prevent it,
and with a token cap the reasoning leaked into the answer text. It was replaced by the
Instruct-2507 variant, which has no thinking mode.

## Setup

    ollama pull qwen3:4b-instruct-2507-q4_K_M
    ollama pull bge-m3

## Smoke test (PowerShell)

    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    $chat = @{ model="qwen3:4b-instruct-2507-q4_K_M"; stream=$false
               messages=@(@{role="user"; content="Say hello in Vietnamese."}) } | ConvertTo-Json -Depth 5
    (Invoke-RestMethod -Uri http://localhost:11434/api/chat -Method Post -Body $chat -ContentType "application/json").message.content

    $emb = @{ model="bge-m3"; input="test" } | ConvertTo-Json
    (Invoke-RestMethod -Uri http://localhost:11434/api/embed -Method Post -Body $emb -ContentType "application/json").embeddings[0].Count

## Known limitations
- Small local models answer worse than cloud models, especially in Vietnamese and for tool calling.
- Throughput is about 12 to 20 tokens/s on CPU, so batch evaluation is slow. Plan to run the
  evaluation set against a cloud model and record which model produced each score.
- The Windows console may show Vietnamese text garbled; this is display only.