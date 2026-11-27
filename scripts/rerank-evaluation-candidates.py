#!/usr/bin/env python3
"""Call a local TEI reranker on tenant-validated EKA evaluation candidates.

The input must contain actual, ordered EKA vector/RRF candidates, mapped to the
fixture-local document IDs. This script does not generate the EKA candidates.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

DEFAULT_DATA = Path(__file__).resolve().parents[1] / "docs/fixtures/sprint-04-reranking"


class CandidateInputError(ValueError):
    """Raised when the candidate export is incomplete or inconsistent."""


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    try:
        with path.open(encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as error:
                    raise CandidateInputError(
                        f"{path}:{line_number}: invalid JSON: {error.msg}"
                    ) from error
                if not isinstance(row, dict):
                    raise CandidateInputError(f"{path}:{line_number}: expected JSON object")
                rows.append(row)
    except OSError as error:
        raise CandidateInputError(f"Cannot read {path}: {error}") from error
    return rows


def unique_index(rows: list[dict[str, Any]], field: str, label: str) -> dict[str, dict[str, Any]]:
    result = {}
    for row in rows:
        value = row.get(field)
        if not isinstance(value, str) or not value:
            raise CandidateInputError(f"{label}: missing non-empty {field}")
        if value in result:
            raise CandidateInputError(f"{label}: duplicate {field} {value!r}")
        result[value] = row
    return result


def rerank(endpoint: str, timeout: float, query: str, texts: list[str]) -> list[int]:
    request_body = json.dumps(
        {"query": query, "texts": texts, "truncate": True}, ensure_ascii=False
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{endpoint.rstrip('/')}/rerank",
        data=request_body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise CandidateInputError(f"TEI rerank request failed: {error}") from error

    if not isinstance(payload, list) or len(payload) != len(texts):
        raise CandidateInputError("TEI returned an invalid ranking count")
    seen = set()
    ranks = []
    for item in payload:
        if not isinstance(item, dict):
            raise CandidateInputError("TEI returned a malformed ranking item")
        index, score = item.get("index"), item.get("score")
        if type(index) is not int or index < 0 or index >= len(texts) or index in seen:
            raise CandidateInputError("TEI returned duplicate or out-of-range candidate indexes")
        if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score):
            raise CandidateInputError("TEI returned an invalid score")
        seen.add(index)
        ranks.append((index, float(score)))
    return [index for index, _score in sorted(ranks, key=lambda rank: (-rank[1], rank[0]))]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", required=True, type=Path, help="JSONL actual EKA RRF candidate export")
    parser.add_argument("--output", required=True, type=Path, help="Output JSONL for evaluate-reranking.py")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8081")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be > 0")
    if not args.endpoint.startswith(("http://", "https://")):
        parser.error("--endpoint must use HTTP or HTTPS")

    try:
        corpus = unique_index(read_jsonl(args.data_dir / "corpus.jsonl"), "doc_id", "corpus")
        queries = unique_index(read_jsonl(args.data_dir / "queries.jsonl"), "query_id", "queries")
        candidate_rows = unique_index(read_jsonl(args.candidates), "query_id", "candidate export")
        missing, unknown = sorted(set(queries) - set(candidate_rows)), sorted(set(candidate_rows) - set(queries))
        if missing or unknown:
            details = []
            if missing:
                details.append(f"missing query rows: {', '.join(missing)}")
            if unknown:
                details.append(f"unknown query ids: {', '.join(unknown)}")
            raise CandidateInputError("; ".join(details))

        outputs = []
        for query_id, query in queries.items():
            candidates = candidate_rows[query_id].get("candidates")
            if not isinstance(candidates, list) or len(candidates) > 10:
                raise CandidateInputError(f"{query_id}: candidates must be an array of at most 10")
            doc_ids, texts = [], []
            for candidate in candidates:
                if not isinstance(candidate, dict):
                    raise CandidateInputError(f"{query_id}: malformed candidate row")
                doc_id, tenant_id, text = (
                    candidate.get("doc_id"), candidate.get("tenant_id"), candidate.get("text")
                )
                if not isinstance(doc_id, str) or doc_id not in corpus:
                    raise CandidateInputError(f"{query_id}: unknown fixture doc_id {doc_id!r}")
                if corpus[doc_id].get("tenant_id") != query.get("tenant_id") or tenant_id != query.get("tenant_id"):
                    raise CandidateInputError(f"{query_id}: cross-tenant candidate rejected before TEI")
                if not isinstance(text, str) or not text.strip() or len(text) > 6000:
                    raise CandidateInputError(f"{query_id}/{doc_id}: text must be 1–6000 characters")
                if doc_id in doc_ids:
                    raise CandidateInputError(f"{query_id}: duplicate candidate {doc_id!r}")
                doc_ids.append(doc_id)
                texts.append(text)

            order = rerank(args.endpoint, args.timeout, query["query"], texts) if texts else []
            outputs.append(
                {
                    "query_id": query_id,
                    "rrf": doc_ids,
                    "reranked": [doc_ids[index] for index in order],
                }
            )

        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in outputs),
            encoding="utf-8",
        )
        temporary.replace(args.output)
        print(f"Wrote {len(outputs)} query rankings to {args.output}")
        print("Candidate provenance is supplied by the caller and is not independently verified.")
        return 0
    except CandidateInputError as error:
        print(f"Invalid candidate input: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"Could not write output: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
