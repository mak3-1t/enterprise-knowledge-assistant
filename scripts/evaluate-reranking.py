#!/usr/bin/env python3
"""Evaluate EKA candidate rankings against a small, synthetic Sprint 4 qrels set.

This script scores caller-provided candidate lists. It does not generate candidates
and cannot independently verify that they came from EKA's vector/RRF pipeline.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


DEFAULT_DATA = Path(__file__).resolve().parents[1] / "docs/fixtures/sprint-04-reranking"


class EvaluationInputError(ValueError):
    """Raised when an input file violates the evaluation schema."""


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
                    raise EvaluationInputError(
                        f"{path}:{line_number}: invalid JSON: {error.msg}"
                    ) from error
                if not isinstance(row, dict):
                    raise EvaluationInputError(
                        f"{path}:{line_number}: each JSONL row must be an object"
                    )
                rows.append(row)
    except OSError as error:
        raise EvaluationInputError(f"Cannot read {path}: {error}") from error
    return rows


def unique_index(rows: list[dict[str, Any]], field: str, label: str) -> dict[str, dict[str, Any]]:
    indexed = {}
    for row in rows:
        value = row.get(field)
        if not isinstance(value, str) or not value:
            raise EvaluationInputError(f"{label}: missing non-empty {field}")
        if value in indexed:
            raise EvaluationInputError(f"{label}: duplicate {field} {value!r}")
        indexed[value] = row
    return indexed


def dcg(grades: list[int]) -> float:
    return sum((2**grade - 1) / math.log2(rank + 1) for rank, grade in enumerate(grades, 1))


def ndcg(ranked: list[str], judgments: dict[str, int], k: int) -> float:
    actual = [judgments.get(doc_id, 0) for doc_id in ranked[:k]]
    ideal = sorted(judgments.values(), reverse=True)[:k]
    ideal_dcg = dcg(ideal)
    return dcg(actual) / ideal_dcg if ideal_dcg else 0.0


def mrr(ranked: list[str], judgments: dict[str, int]) -> float:
    for rank, doc_id in enumerate(ranked, 1):
        if judgments.get(doc_id, 0) >= 1:
            return 1.0 / rank
    return 0.0


def recall(ranked: list[str], judgments: dict[str, int], k: int) -> float:
    relevant = {doc_id for doc_id, grade in judgments.items() if grade >= 1}
    if not relevant:
        return 0.0
    return len(relevant.intersection(ranked[:k])) / len(relevant)


def validate_and_score(args: argparse.Namespace) -> int:
    corpus_rows = read_jsonl(args.data_dir / "corpus.jsonl")
    query_rows = read_jsonl(args.data_dir / "queries.jsonl")
    qrel_rows = read_jsonl(args.data_dir / "qrels.jsonl")
    result_rows = read_jsonl(args.results)

    corpus = unique_index(corpus_rows, "doc_id", "corpus")
    queries = unique_index(query_rows, "query_id", "queries")
    if not corpus or not queries:
        raise EvaluationInputError("Corpus and queries must not be empty")

    for doc_id, doc in corpus.items():
        if not isinstance(doc.get("tenant_id"), str) or not doc["tenant_id"]:
            raise EvaluationInputError(f"corpus: {doc_id!r} has no tenant_id")
        if not isinstance(doc.get("text"), str) or not doc["text"].strip():
            raise EvaluationInputError(f"corpus: {doc_id!r} has no text")

    for query_id, query in queries.items():
        if not isinstance(query.get("tenant_id"), str) or not query["tenant_id"]:
            raise EvaluationInputError(f"queries: {query_id!r} has no tenant_id")
        if not isinstance(query.get("query"), str) or not query["query"].strip():
            raise EvaluationInputError(f"queries: {query_id!r} has no query text")
        if not isinstance(query.get("expect_no_relevant_doc"), bool):
            raise EvaluationInputError(
                f"queries: {query_id!r} expect_no_relevant_doc must be boolean"
            )

    judgments: dict[str, dict[str, int]] = defaultdict(dict)
    for row in qrel_rows:
        query_id, doc_id, grade = row.get("query_id"), row.get("doc_id"), row.get("grade")
        if query_id not in queries or doc_id not in corpus:
            raise EvaluationInputError(f"qrels: unknown query_id/doc_id pair {query_id!r}/{doc_id!r}")
        if type(grade) is not int or grade not in (0, 1, 2):
            raise EvaluationInputError(f"qrels: grade for {query_id!r}/{doc_id!r} must be 0, 1, or 2")
        if doc_id in judgments[query_id]:
            raise EvaluationInputError(f"qrels: duplicate judgment for {query_id!r}/{doc_id!r}")
        if grade > 0 and corpus[doc_id]["tenant_id"] != queries[query_id]["tenant_id"]:
            raise EvaluationInputError(f"qrels: positive cross-tenant judgment {query_id!r}/{doc_id!r}")
        if grade > 0 and queries[query_id]["expect_no_relevant_doc"]:
            raise EvaluationInputError(f"qrels: no-answer query {query_id!r} has positive relevance")
        judgments[query_id][doc_id] = grade

    answerable = {query_id for query_id, query in queries.items() if not query["expect_no_relevant_doc"]}
    for query_id in answerable:
        if not any(grade > 0 for grade in judgments[query_id].values()):
            raise EvaluationInputError(f"qrels: answerable query {query_id!r} has no relevant document")

    results = unique_index(result_rows, "query_id", "results")
    unknown_queries = sorted(set(results) - set(queries))
    missing_queries = sorted(set(queries) - set(results))
    if unknown_queries or missing_queries:
        details = []
        if missing_queries:
            details.append(f"missing results for {len(missing_queries)} queries: {', '.join(missing_queries)}")
        if unknown_queries:
            details.append(f"unknown query ids: {', '.join(unknown_queries)}")
        raise EvaluationInputError("; ".join(details))

    leaks = []
    unjudged = 0
    no_answer_counts = {"rrf": 0, "reranked": 0}
    scores = {stage: {metric: [] for metric in ("ndcg", "mrr", "recall")} for stage in ("rrf", "reranked")}
    for query_id, query in queries.items():
        result = results[query_id]
        candidate_lists = {}
        for stage in ("rrf", "reranked"):
            ranked = result.get(stage)
            if not isinstance(ranked, list) or any(not isinstance(doc_id, str) for doc_id in ranked):
                raise EvaluationInputError(f"results: {query_id!r} {stage} must be an array of document ids")
            if len(ranked) > 10:
                raise EvaluationInputError(f"results: {query_id!r} {stage} exceeds EKA's 10-candidate bound")
            if len(ranked) != len(set(ranked)):
                raise EvaluationInputError(f"results: duplicate document id in {query_id!r} {stage}")
            unknown_docs = sorted(set(ranked) - set(corpus))
            if unknown_docs:
                raise EvaluationInputError(f"results: unknown document ids for {query_id!r} {stage}: {unknown_docs}")
            foreign = [doc_id for doc_id in ranked if corpus[doc_id]["tenant_id"] != query["tenant_id"]]
            if foreign:
                leaks.extend((query_id, stage, doc_id) for doc_id in foreign)
            candidate_lists[stage] = ranked

        if set(candidate_lists["rrf"]) != set(candidate_lists["reranked"]):
            raise EvaluationInputError(
                f"results: reranked candidates for {query_id!r} must be an exact permutation of rrf candidates"
            )

        if query["expect_no_relevant_doc"]:
            for stage in no_answer_counts:
                no_answer_counts[stage] += bool(candidate_lists[stage])
            continue

        query_judgments = judgments[query_id]
        for stage, ranked in candidate_lists.items():
            unjudged += sum(doc_id not in query_judgments for doc_id in ranked)
            scores[stage]["ndcg"].append(ndcg(ranked, query_judgments, args.k))
            scores[stage]["mrr"].append(mrr(ranked, query_judgments))
            scores[stage]["recall"].append(recall(ranked, query_judgments, args.k))

    if leaks:
        print(f"FAIL: cross-tenant candidate leakage ({len(leaks)} entries; required 0)")
        for query_id, stage, doc_id in leaks:
            print(f"  query={query_id} stage={stage} foreign_doc={doc_id}")
        return 1

    print(f"Synthetic evaluation: {len(corpus)} documents, {len(queries)} queries, k={args.k}")
    print(f"Candidate source: {args.candidate_source or 'unspecified; not independently verified'}")
    print(f"Answerable queries scored: {len(answerable)}")
    print(f"Unjudged ranked document occurrences (treated as grade 0): {unjudged}")
    for stage in ("rrf", "reranked"):
        values = scores[stage]
        print(
            f"{stage:8s} nDCG@{args.k}={sum(values['ndcg']) / len(answerable):.4f} "
            f"MRR={sum(values['mrr']) / len(answerable):.4f} "
            f"Recall@{args.k}={sum(values['recall']) / len(answerable):.4f}"
        )
    no_answer_total = sum(query["expect_no_relevant_doc"] for query in queries.values())
    print(
        "No-answer/cross-tenant queries with any candidates after retrieval: "
        f"RRF={no_answer_counts['rrf']}/{no_answer_total}, "
        f"reranked={no_answer_counts['reranked']}/{no_answer_total}"
    )
    print("Cross-tenant leakage: 0 (required)")
    print("Scope: synthetic fixture only; not production corpus quality evidence.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path, help="JSONL ranks from the EKA vector/RRF pipeline")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA, help="Directory containing corpus, queries and qrels JSONL")
    parser.add_argument("--candidate-source", help="Human-readable source/version for the ranked candidate lists")
    parser.add_argument("--k", type=int, default=5)
    args = parser.parse_args()
    if args.k < 1:
        parser.error("--k must be >= 1")
    try:
        return validate_and_score(args)
    except EvaluationInputError as error:
        print(f"Invalid evaluation input: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
