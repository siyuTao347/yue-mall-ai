#!/usr/bin/env python3
"""Run retrieval, answer-generation, and RAGAS 0.2 evaluation for mall-agent RAG."""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
import time
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

import requests


EVALUATION_DIR = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = EVALUATION_DIR / "datasets" / "golden-questions.jsonl"
DEFAULT_OUTPUT = EVALUATION_DIR / "outputs"

DEFAULT_THRESHOLDS = {
    "hit_at_1": 0.70,
    "hit_at_3": 0.85,
    "hit_at_5": 0.90,
    "mrr": 0.75,
    "context_coverage": 0.80,
    "faithfulness": 0.90,
    "answer_relevancy": 0.80,
    "context_precision": 0.75,
    "context_recall": 0.80,
    "latency_p95_ms": 3000,
    "error_rate": 0.02,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--input-raw", type=Path)
    parser.add_argument("--input-ragas", type=Path)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--run-name", default=None)
    parser.add_argument("--base-url", default=os.getenv("RAG_BASE_URL", "http://localhost:8085"))
    parser.add_argument("--admin-token", default=os.getenv("ADMIN_TOKEN", ""))
    parser.add_argument("--top-k", type=int, default=20)
    parser.add_argument("--parent-top-n", type=int, default=5)
    parser.add_argument("--doc-types", default="PRODUCT")
    parser.add_argument("--mode", choices=["VECTOR"], default="VECTOR")
    parser.add_argument("--query-type", action="append")
    parser.add_argument("--visibility", choices=["PUBLIC", "INTERNAL"])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--no-ragas", action="store_true")
    parser.add_argument("--ragas-only", action="store_true")
    parser.add_argument("--answer-retries", type=int, default=3)
    parser.add_argument("--answer-retry-delay-seconds", type=float, default=2.0)
    parser.add_argument(
        "--ragas-timeout-seconds",
        type=int,
        default=int(os.environ.get("RAG_EVAL_LLM_TIMEOUT_SECONDS", "180")),
    )
    parser.add_argument(
        "--ragas-retries",
        type=int,
        default=int(os.environ.get("RAG_EVAL_MAX_RETRIES", "5")),
    )
    parser.add_argument("--fail-on-quality", action="store_true")
    parser.add_argument("--timeout-seconds", type=float, default=20)
    parser.add_argument("--max-context-chars", type=int, default=6000)
    return parser.parse_args()


def load_records(path: Path, query_types: list[str] | None, visibility: str | None, limit: int | None) -> list[dict]:
    if not path.exists():
        raise FileNotFoundError(
            f"Golden dataset not found: {path}. Run scripts/build_golden_dataset.py first."
        )
    records = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"Invalid JSON at {path}:{line_number}: {error}") from error
            if query_types and record.get("query_type") not in query_types:
                continue
            if visibility and record.get("visibility") != visibility:
                continue
            records.append(record)
    if limit:
        records = records[:limit]
    if not records:
        raise ValueError("No golden records selected")
    return records


def normalize_base_url(value: str) -> str:
    return value.rstrip("/")


def retrieval_request(
    query: str,
    base_url: str,
    token: str,
    mode: str,
    doc_types: list[str],
    top_k: int,
    parent_top_n: int,
    timeout: float,
) -> dict[str, Any]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = {
        "query": query,
        "mode": mode,
        "docTypes": doc_types,
        "topK": top_k,
        "parentTopN": parent_top_n,
    }
    response = requests.post(
        f"{normalize_base_url(base_url)}/api/admin/agent/knowledge/retrieval-test",
        headers=headers,
        json=payload,
        timeout=timeout,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"HTTP {response.status_code}: {response.text[:500]}")
    body = response.json()
    if body.get("code") != 200:
        raise RuntimeError(
            f"API error {body.get('errorCode') or body.get('code')}: {body.get('msg')} "
            f"(traceId={body.get('traceId')})"
        )
    return body["data"]


def evaluate_retrieval(record: dict, result: dict[str, Any]) -> dict[str, Any]:
    expected = set(record["expected_doc_nos"])
    child_hits = result.get("childHits", [])
    hit_doc_nos = []
    for rank, hit in enumerate(child_hits, start=1):
        if hit.get("docNo") in expected:
            hit_doc_nos.append((rank, hit["docNo"]))

    first_rank = hit_doc_nos[0][0] if hit_doc_nos else math.inf
    metrics: dict[str, Any] = {
        "hit_at_1": 1.0 if first_rank <= 1 else 0.0,
        "hit_at_3": 1.0 if first_rank <= 3 else 0.0,
        "hit_at_5": 1.0 if first_rank <= 5 else 0.0,
        "hit_at_10": 1.0 if first_rank <= 10 else 0.0,
        "mrr": 0.0 if math.isinf(first_rank) else 1.0 / first_rank,
        "context_coverage": min(1.0, len({doc_no for _, doc_no in hit_doc_nos}) / len(expected)),
    }
    return metrics


def percentile(values: list[float], percentile_value: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile_value
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    if lower == upper:
        return float(ordered[lower])
    return float(ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower))


def average(values: list[float]) -> float:
    return float(sum(values) / len(values)) if values else 0.0


def generate_answer(
    query: str,
    contexts: list[str],
    base_url: str,
    api_key: str,
    model: str,
    timeout: float,
    retries: int,
    retry_delay: float,
) -> str:
    if not contexts:
        return "未找到对应资料，无法回答该问题。"
    context_text = "\n\n".join(
        f"[{index + 1}] {context[:2000]}" for index, context in enumerate(contexts)
    )
    messages = [
        {
            "role": "system",
            "content": (
                "你是虚拟资产交易平台的客服助手。只能依据提供的资料回答，"
                "不得编造价格、库存、交付方式、审核状态或平台规则。"
            ),
        },
        {
            "role": "user",
            "content": (
                f"用户问题：{query}\n\n检索资料：\n{context_text}\n\n"
                "要求：1. 用中文简洁回答；2. 关键事实引用资料编号，例如 [1]；"
                "3. 如资料不足，明确说明未找到完整信息。"
            ),
        },
    ]
    last_error: Exception | None = None
    for attempt in range(1, max(1, retries) + 1):
        try:
            response = requests.post(
                f"{normalize_base_url(base_url)}/v1/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json={"model": model, "messages": messages, "temperature": 0, "max_tokens": 700},
                timeout=timeout,
            )
            if response.status_code >= 400:
                raise RuntimeError(f"HTTP {response.status_code}: {response.text[:500]}")
            choices = response.json().get("choices", [])
            if not choices:
                raise RuntimeError("Answer model returned no choices")
            answer = str(choices[0].get("message", {}).get("content", "")).strip()
            if not answer:
                raise RuntimeError("Answer model returned an empty answer")
            return answer
        except Exception as error:
            last_error = error
            if attempt < max(1, retries):
                print(f"Answer generation failed (attempt {attempt}/{retries}): {error}", file=sys.stderr)
                time.sleep(retry_delay)
    raise RuntimeError(f"Answer generation failed after {max(1, retries)} attempts: {last_error}")


def load_ragas_metrics() -> list[Any]:
    try:
        from ragas.metrics import (  # type: ignore
            AnswerRelevancy,
            ContextPrecision,
            ContextRecall,
            Faithfulness,
        )

        return [Faithfulness(), ContextPrecision(), ContextRecall(), AnswerRelevancy()]
    except ImportError:
        from ragas.metrics import (  # type: ignore
            answer_relevancy,
            context_precision,
            context_recall,
            faithfulness,
        )

        return [faithfulness, context_precision, context_recall, answer_relevancy]


def run_ragas(
    records: list[dict[str, Any]],
    existing_rows: list[dict[str, Any]] | None = None,
    timeout_seconds: int = 180,
    retries: int = 5,
) -> tuple[dict[str, float], list[dict[str, Any]]]:
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings  # type: ignore
    from ragas import EvaluationDataset, RunConfig, evaluate  # type: ignore
    from ragas.embeddings import LangchainEmbeddingsWrapper  # type: ignore
    from ragas.llms import LangchainLLMWrapper  # type: ignore
    import pandas as pd  # type: ignore

    llm_base_url = os.environ.get("RAG_EVAL_LLM_BASE_URL", "")
    llm_api_key = os.environ.get("RAG_EVAL_LLM_API_KEY", "")
    llm_model = os.environ.get("RAG_EVAL_LLM_MODEL", "")
    embedding_base_url = os.environ.get("RAG_EVAL_EMBEDDING_BASE_URL", "")
    embedding_api_key = os.environ.get("RAG_EVAL_EMBEDDING_API_KEY", "")
    embedding_model = os.environ.get("RAG_EVAL_EMBEDDING_MODEL", "")
    embedding_dim = int(os.environ.get("RAG_EVAL_EMBEDDING_DIM", "1536"))
    missing = [name for name, value in {
        "RAG_EVAL_LLM_BASE_URL": llm_base_url,
        "RAG_EVAL_LLM_API_KEY": llm_api_key,
        "RAG_EVAL_LLM_MODEL": llm_model,
        "RAG_EVAL_EMBEDDING_BASE_URL": embedding_base_url,
        "RAG_EVAL_EMBEDDING_API_KEY": embedding_api_key,
        "RAG_EVAL_EMBEDDING_MODEL": embedding_model,
    }.items() if not value]
    if missing:
        raise RuntimeError("Missing RAGAS environment variables: " + ", ".join(missing))

    ragas_records = [
        {
            "user_input": record["query"],
            "response": record["answer"],
            "retrieved_contexts": record["contexts"],
            "reference": record["ground_truth"],
        }
        for record in records
    ]
    llm = LangchainLLMWrapper(
        ChatOpenAI(
            model=llm_model,
            api_key=llm_api_key,
            base_url=f"{normalize_base_url(llm_base_url)}/v1",
            temperature=0,
        )
    )
    embeddings = LangchainEmbeddingsWrapper(
        OpenAIEmbeddings(
            model=embedding_model,
            api_key=embedding_api_key,
            base_url=f"{normalize_base_url(embedding_base_url)}/v1",
            dimensions=embedding_dim,
            check_embedding_ctx_length=False,
        )
    )
    metric_instances = {metric.name: metric for metric in load_ragas_metrics()}
    metric_names = ["faithfulness", "context_precision", "context_recall", "answer_relevancy"]
    run_config = RunConfig(
        max_workers=int(os.environ.get("RAG_EVAL_MAX_WORKERS", "2")),
        max_retries=max(1, retries),
        timeout=max(30, timeout_seconds),
    )

    if existing_rows is None:
        dataset = EvaluationDataset.from_list(ragas_records)
        result = evaluate(
            dataset=dataset,
            metrics=list(metric_instances.values()),
            llm=llm,
            embeddings=embeddings,
            show_progress=True,
            run_config=run_config,
        )
        dataframe = result.to_pandas()
    else:
        dataframe = pd.DataFrame(existing_rows)
        if len(dataframe) != len(records):
            raise RuntimeError(
                f"Existing RAGAS rows do not match raw records: "
                f"{len(dataframe)} != {len(records)}"
            )
        for metric_name in metric_names:
            if metric_name not in dataframe:
                dataframe[metric_name] = float("nan")

        for metric_name in metric_names:
            metric = metric_instances[metric_name]
            for attempt in range(1, 3):
                missing_indices = dataframe.index[dataframe[metric_name].isna()].tolist()
                if not missing_indices:
                    break
                print(
                    f"[RAGAS resume] {metric_name}: retrying {len(missing_indices)} rows "
                    f"(attempt {attempt}/2, timeout={max(30, timeout_seconds)}s)"
                )
                subset = [ragas_records[index] for index in missing_indices]
                result = evaluate(
                    dataset=EvaluationDataset.from_list(subset),
                    metrics=[metric],
                    llm=llm,
                    embeddings=embeddings,
                    show_progress=True,
                    run_config=run_config,
                )
                scores = result.to_pandas()[metric_name]
                if len(scores) != len(missing_indices):
                    raise RuntimeError(
                        f"{metric_name} retry returned {len(scores)} scores for "
                        f"{len(missing_indices)} rows"
                    )
                dataframe.loc[missing_indices, metric_name] = scores.to_numpy()

    rows = dataframe.to_dict(orient="records")
    summary = {}
    for metric_name in metric_names:
        if metric_name in dataframe:
            values = dataframe[metric_name].dropna().astype(float).tolist()
            summary[metric_name] = average(values)
            summary[f"{metric_name}_coverage"] = len(values) / len(dataframe) if len(dataframe) else 0.0
    return summary, rows


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def write_csv(path: Path, records: list[dict[str, Any]]) -> None:
    if not records:
        return
    fields = sorted({key for record in records for key in record})
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(records)


def summarize(records: list[dict[str, Any]], ragas_summary: dict[str, float]) -> dict[str, Any]:
    successful = [record for record in records if record.get("success")]
    latencies = [float(record["latency_ms"]) for record in successful]
    summary: dict[str, Any] = {
        "total": len(records),
        "successful": len(successful),
        "failed": len(records) - len(successful),
        "error_rate": (len(records) - len(successful)) / len(records) if records else 1.0,
        "latency_avg_ms": round(average(latencies), 2),
        "latency_p50_ms": round(percentile(latencies, 0.50), 2),
        "latency_p95_ms": round(percentile(latencies, 0.95), 2),
    }
    for metric_name in ["hit_at_1", "hit_at_3", "hit_at_5", "hit_at_10", "mrr", "context_coverage"]:
        summary[metric_name] = round(average([record[metric_name] for record in successful]), 4)
    for metric_name, value in ragas_summary.items():
        summary[metric_name] = round(value, 4)
    return summary


def group_summary(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        if record.get("success"):
            groups[f"{record.get('query_type', 'unknown')} / {record.get('visibility', 'unknown')}"].append(record)
    rows = []
    for group_name, items in sorted(groups.items()):
        rows.append(
            {
                "group": group_name,
                "count": len(items),
                "hit_at_5": round(average([item["hit_at_5"] for item in items]), 4),
                "mrr": round(average([item["mrr"] for item in items]), 4),
                "context_coverage": round(average([item["context_coverage"] for item in items]), 4),
            }
        )
    return rows


def threshold_check(summary: dict[str, Any], ragas_enabled: bool) -> dict[str, Any]:
    results = {}
    minimum_score_coverage = 0.90
    for metric_name, expected in DEFAULT_THRESHOLDS.items():
        if metric_name in {"faithfulness", "answer_relevancy", "context_precision", "context_recall"} and not ragas_enabled:
            continue
        if ragas_enabled and metric_name in {"faithfulness", "answer_relevancy", "context_precision", "context_recall"}:
            coverage = summary.get(f"{metric_name}_coverage", 0.0)
            results[f"{metric_name}_coverage"] = {
                "expected_min": minimum_score_coverage,
                "actual": coverage,
                "passed": coverage >= minimum_score_coverage,
            }
        if metric_name == "latency_p95_ms":
            results[metric_name] = {"expected_max": expected, "actual": summary.get(metric_name), "passed": summary.get(metric_name, math.inf) <= expected}
        elif metric_name == "error_rate":
            results[metric_name] = {"expected_max": expected, "actual": summary.get(metric_name), "passed": summary.get(metric_name, 1.0) <= expected}
        else:
            results[metric_name] = {"expected_min": expected, "actual": summary.get(metric_name), "passed": summary.get(metric_name, 0.0) >= expected}
    return {"passed": all(item["passed"] for item in results.values()), "checks": results}


def write_report(
    path: Path,
    run_name: str,
    args: argparse.Namespace,
    summary: dict[str, Any],
    groups: list[dict[str, Any]],
    thresholds: dict[str, Any],
    ragas_enabled: bool,
    ragas_error: str | None,
    records: list[dict[str, Any]],
) -> None:
    lines = [
        "# RAG Evaluation Report",
        "",
        f"- Run: `{run_name}`",
        f"- Time: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- API: `{args.base_url}`",
        f"- Mode: `{args.mode}`",
        f"- Child TopK: `{args.top_k}`",
        f"- Parent TopN: `{args.parent_top_n}`",
        f"- RAGAS: `{'enabled' if ragas_enabled else 'disabled'}`",
        *( [f"- RAGAS error: `{ragas_error}`"] if ragas_error else [] ),
        "",
        "## Summary",
        "",
        "| Metric | Value | Threshold | Result |",
        "|---|---:|---:|---|",
    ]
    for metric_name, check in thresholds["checks"].items():
        threshold = check.get("expected_min", check.get("expected_max"))
        lines.append(
            f"| {metric_name} | {check['actual']} | {threshold} | {'PASS' if check['passed'] else 'FAIL'} |"
        )
    lines.extend(["", "## By Query Type / Visibility", "", "| Group | Count | Hit@5 | MRR | Context Coverage |", "|---|---:|---:|---:|---:|"])
    for row in groups:
        lines.append(
            f"| {row['group']} | {row['count']} | {row['hit_at_5']} | {row['mrr']} | {row['context_coverage']} |"
        )
    lines.extend(["", "## Lowest Performing Cases", ""])
    failed = [record for record in records if not record.get("success")]
    if failed:
        lines.append("### API failures")
        for record in failed:
            lines.append(f"- `{record['id']}`: {record.get('error')}")
    weakest = sorted(
        (record for record in records if record.get("success")),
        key=lambda record: (record["hit_at_5"], record["mrr"], record["context_coverage"]),
    )[:10]
    if weakest:
        lines.extend(["", "### Retrieval misses", ""])
        for record in weakest:
            lines.append(
                f"- `{record['id']}` Hit@5={record['hit_at_5']}, MRR={record['mrr']:.3f}, "
                f"Coverage={record['context_coverage']:.3f}: {record['query']}"
            )
    lines.extend(["", "## Recommendation", ""])
    if thresholds["passed"]:
        lines.append("- Overall quality meets the configured enterprise threshold.")
    else:
        failed_metrics = [name for name, check in thresholds["checks"].items() if not check["passed"]]
        lines.append(f"- Failed metrics: {', '.join(failed_metrics)}. Prioritize these before release.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if not args.ragas_only and not args.admin_token:
        print("ADMIN_TOKEN is required for the admin retrieval API.", file=sys.stderr)
        return 2
    if args.ragas_only and not args.input_raw:
        print("--input-raw is required with --ragas-only.", file=sys.stderr)
        return 2

    run_name = args.run_name or datetime.now().strftime("%Y%m%d-%H%M%S")
    output_dir = args.output_dir / run_name
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.ragas_only:
        with args.input_raw.open(encoding="utf-8") as handle:
            raw_records = [json.loads(line) for line in handle if line.strip()]
        if not raw_records:
            print("Input raw-results file is empty.", file=sys.stderr)
            return 2
        required_fields = {"query", "answer", "contexts", "ground_truth"}
        invalid_rows = [
            index for index, record in enumerate(raw_records, start=1)
            if not required_fields.issubset(record) or not record["answer"] or not record["contexts"]
        ]
        if invalid_rows:
            print(
                f"Input raw-results contains incomplete rows: {invalid_rows[:10]}",
                file=sys.stderr,
            )
            return 2
        existing_ragas_rows = None
        if args.input_ragas:
            import pandas as pd  # type: ignore

            existing_ragas_rows = pd.read_csv(args.input_ragas).to_dict(orient="records")
            if not existing_ragas_rows:
                print("Input ragas-results file is empty.", file=sys.stderr)
                return 2
    else:
        records = load_records(args.dataset, args.query_type, args.visibility, args.limit)
        doc_types = [item.strip().upper() for item in args.doc_types.split(",") if item.strip()]
        raw_records = []
        for index, record in enumerate(records, start=1):
            print(f"[{index}/{len(records)}] {record['id']}: {record['query']}")
            result_record: dict[str, Any] = {
                **record,
                "success": False,
                "contexts": [],
                "answer": "",
                "latency_ms": 0,
            }
            try:
                started = time.perf_counter()
                result = retrieval_request(
                    record["query"], args.base_url, args.admin_token, args.mode, doc_types,
                    args.top_k, args.parent_top_n, args.timeout_seconds,
                )
                result_record["latency_ms"] = round((time.perf_counter() - started) * 1000, 2)
                contexts = [context.get("content", "") for context in result.get("contexts", [])]
                context_budget = 0
                for context in contexts:
                    if context_budget >= args.max_context_chars:
                        break
                    result_record["contexts"].append(context[: args.max_context_chars - context_budget])
                    context_budget += len(result_record["contexts"][-1])
                result_record["child_hits"] = result.get("childHits", [])
                result_record["context_count"] = len(result_record["contexts"])
                result_record["context_chars"] = sum(len(context) for context in result_record["contexts"])
                result_record.update(evaluate_retrieval(record, result))
                result_record["success"] = True
            except Exception as error:
                result_record["error"] = str(error)
                result_record.update({
                    "hit_at_1": 0.0, "hit_at_3": 0.0, "hit_at_5": 0.0, "hit_at_10": 0.0,
                    "mrr": 0.0, "context_coverage": 0.0,
                })
            raw_records.append(result_record)

    ragas_summary: dict[str, float] = {}
    ragas_rows: list[dict[str, Any]] = []
    ragas_enabled = False
    ragas_error: str | None = None
    if not args.no_ragas:
        required_ragas_env = [
            "RAG_EVAL_LLM_BASE_URL", "RAG_EVAL_LLM_API_KEY", "RAG_EVAL_LLM_MODEL",
            "RAG_EVAL_EMBEDDING_BASE_URL", "RAG_EVAL_EMBEDDING_API_KEY",
            "RAG_EVAL_EMBEDDING_MODEL", "RAG_EVAL_EMBEDDING_DIM",
        ]
        missing_ragas_env = [name for name in required_ragas_env if not os.environ.get(name)]
        if missing_ragas_env:
            ragas_error = "Missing RAGAS environment variables: " + ", ".join(missing_ragas_env)
            print(ragas_error, file=sys.stderr)
        else:
            try:
                for record in raw_records:
                    if not record["answer"]:
                        record["answer"] = generate_answer(
                            record["query"],
                            record["contexts"],
                            os.environ["RAG_EVAL_LLM_BASE_URL"],
                            os.environ["RAG_EVAL_LLM_API_KEY"],
                            os.environ["RAG_EVAL_LLM_MODEL"],
                            args.timeout_seconds,
                            args.answer_retries,
                            args.answer_retry_delay_seconds,
                        )
                ragas_summary, ragas_rows = run_ragas(
                    raw_records,
                    existing_ragas_rows,
                    args.ragas_timeout_seconds,
                    args.ragas_retries,
                )
                ragas_enabled = True
            except Exception as error:
                ragas_error = f"{type(error).__name__}: {error}"
                print(f"RAGAS evaluation failed: {ragas_error}", file=sys.stderr)

    summary = summarize(raw_records, ragas_summary)
    groups = group_summary(raw_records)
    thresholds = threshold_check(summary, ragas_enabled)
    summary["thresholds_passed"] = thresholds["passed"]

    write_jsonl(output_dir / "raw-results.jsonl", raw_records)
    write_csv(output_dir / "ragas-results.csv", ragas_rows)
    (output_dir / "summary.json").write_text(
        json.dumps(
            {"summary": summary, "groups": groups, "thresholds": thresholds, "ragas_error": ragas_error},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    write_report(
        output_dir / "report.md", run_name, args, summary, groups, thresholds, ragas_enabled,
        ragas_error, raw_records
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Output directory: {output_dir}")
    if args.fail_on_quality and not thresholds["passed"]:
        return 1
    if not args.no_ragas and not ragas_enabled:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
