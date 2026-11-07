# Document chunking

EKA-9 splits private parsed source text before embedding. The ingestion service reads sections
for the requested tenant, document and current source generation. A stale generation,
deleted document or foreign tenant returns no chunks. Downstream writers must recheck the
generation when they commit, since a source can change after it is read.

| Property | Default | Accepted values |
|---|---:|---|
| `eka.ingestion.chunking.strategy` | `recursive` | `fixed` or `recursive` |
| `eka.ingestion.chunking.size` | 512 | 64–4096 Unicode code points |
| `eka.ingestion.chunking.overlap` | 64 | 0 through half the size |

Environment overrides use `EKA_INGESTION_CHUNKING_STRATEGY`, `EKA_INGESTION_CHUNKING_SIZE`
and `EKA_INGESTION_CHUNKING_OVERLAP`. Invalid settings fail at startup.

Fixed uses the full window. Recursive prefers a paragraph, line, sentence or whitespace
boundary in the latter part of the window, then falls back to a hard split. Both retain
original whitespace and never split a surrogate pair. Each chunk records tenant, document,
source generation, chunk ordinal, section ordinal, nullable page and half-open UTF-16 offsets
within its section. Chunks never cross sections. Overlap repeats exactly the configured number
of code points within a section. No chunks are stored or exposed over HTTP in this story.
Future retrieval must still enforce tenant and document access.

## Measured comparison

Java 21, three synthetic sections (Vietnamese onboarding, English operations, Markdown with
JSON and Japanese text), totaling 23,135 Unicode code points. The corpus and measurement
protocol are defined in the test-source `ChunkComparison` class. It compares windows with
12.5% overlap. Word segments use JDK `BreakIterator.getWordInstance(Locale.ROOT)`, counting
segments that start with a letter or digit. They approximate text volume and are not embedding
model tokens. Repeated text is measured as the extra code points emitted relative to the corpus.

| Strategy | Window (code points) | Chunks | Extra text | Mean word segments/chunk |
|---|---:|---:|---:|---:|
| Fixed | 256 | 105 | 14.11% | 45.77 |
| Fixed | 512 | 53 | 13.83% | 89.85 |
| Fixed | 1024 | 27 | 13.28% | 175.04 |
| Recursive | 256 | 138 | 18.67% | 35.72 |
| Recursive | 512 | 58 | 15.22% | 82.64 |
| Recursive | 1024 | 28 | 13.83% | 168.71 |

On this corpus, recursive 512 produced five more chunks than fixed 512. The 512/64 default
is a starting point for preserving boundaries without the chunk count of smaller windows.
These measurements do not rank retrieval quality or establish an embedding token budget.

Run after compiling the project:

```powershell
java -cp "ingestion/target/test-classes;ingestion/target/classes" com.eka.ingestion.internal.chunking.ChunkComparison
```
