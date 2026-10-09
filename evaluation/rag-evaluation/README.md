# RAG Enterprise Evaluation

This directory evaluates the `mall-agent-service` RAG pipeline with RAGAS `0.2.x`.
The golden set is derived from the authoritative source documents in `doc/data/RAG`,
so every expected document and answer is traceable to the current corpus version.

## Evaluation Layers

### 1. Deterministic Retrieval Metrics

These metrics are calculated directly from `/api/admin/agent/knowledge/retrieval-test`:

| Metric | Meaning | Release Threshold |
|---|---|---:|
| `hit_at_1` | Expected document appears at child rank 1 | `>= 0.70` |
| `hit_at_3` | Expected document appears in top 3 | `>= 0.85` |
| `hit_at_5` | Expected document appears in top 5 | `>= 0.90` |
| `mrr` | Mean reciprocal rank | `>= 0.75` |
| `context_coverage` | Coverage of all expected documents | `>= 0.80` |
| `latency_p95_ms` | Retrieval API p95 latency | `<= 3000` |
| `error_rate` | API or parsing failure rate | `<= 0.02` |

### 2. RAGAS Generation Quality Metrics

When LLM environment variables are configured, the script generates an answer from
retrieved parent chunks and runs RAGAS:

| Metric | Meaning | Release Threshold |
|---|---|---:|
| `faithfulness` | Answer is supported by retrieved contexts | `>= 0.90` |
| `answer_relevancy` | Answer addresses the user query | `>= 0.80` |
| `context_precision` | Useful contexts rank higher | `>= 0.75` |
| `context_recall` | Retrieved contexts cover ground truth | `>= 0.80` |

RAGAS is automatically skipped when `RAG_EVAL_LLM_*` is incomplete; deterministic
retrieval metrics still run. Use `--no-ragas` to explicitly disable it.

RAGAS can return `NaN` for individual rows when the judge model fails to produce
a parseable score. The script therefore reports both the valid-score mean and
`<metric>_coverage`. A run fails the enterprise gate when RAGAS score coverage
is below `0.90`, even if the valid-score mean meets the metric threshold.

The default judge configuration uses `DeepSeek-v4-pro` for answer generation and
RAGAS grading. The embedding model used by RAGAS `answer_relevancy` remains a
separate configuration (`RAG_EVAL_EMBEDDING_MODEL`).
Set `RAG_EVAL_MAX_WORKERS=1` if the model provider returns HTTP 429 rate-limit errors.

## Setup

```bash
cd evaluation/rag-evaluation
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp config/env.example .env
set -a; source .env; set +a
```

Required service state:

1. `mall-agent-service` is running.
2. The 18 `doc/data/RAG/*.md` documents have been imported and re-embedded.
3. The retrieval API returns `INDEXING=0` and all expected documents are `READY`.
4. `ADMIN_TOKEN` is a valid administrator JWT.

## Build The Golden Dataset

```bash
python3 scripts/build_golden_dataset.py
```

The generator creates three product-level questions per source document and merges
the manually curated cross-product and guardrail cases from
`datasets/extended-golden-questions.jsonl`. The output is
`datasets/golden-questions.jsonl`.

The default query mix covers:

- Exact product identification.
- Attribute retrieval: price, stock, delivery mode, merchant.
- Semantic and merchant-constrained phrasing.
- Cross-product comparison and constraint questions.
- Audit/shelf-status guardrails.
- `PUBLIC` and `INTERNAL` visibility.

## Run Evaluation

All questions:

```bash
python3 scripts/run_evaluation.py --fail-on-quality
```

Public user-facing subset only:

```bash
python3 scripts/run_evaluation.py --visibility PUBLIC --fail-on-quality
```

Retrieval-only smoke test:

```bash
python3 scripts/run_evaluation.py --no-ragas --limit 20
```

RAGAS-only rerun when retrieval and answer generation have already completed:

```bash
python3 scripts/run_evaluation.py \
  --ragas-only \
  --input-raw outputs/<run-name>/raw-results.jsonl \
  --run-name <run-name>-ragas-fixed
```

This avoids repeating retrieval API calls and answer generation, and only reruns
the four RAGAS metrics.

Specific query types:

```bash
python3 scripts/run_evaluation.py --query-type semantic --query-type comparison
```

Useful options:

| Option | Purpose |
|---|---|
| `--base-url` | Direct agent service or gateway URL |
| `--top-k` | Child chunk recall count, default 20 |
| `--parent-top-n` | Parent context count, default 5 |
| `--limit` | Run a bounded subset |
| `--no-ragas` | Skip answer generation and RAGAS |
| `--ragas-only` | Reuse an existing `raw-results.jsonl` and rerun RAGAS |
| `--input-raw` | Input `raw-results.jsonl` for `--ragas-only` |
| `--fail-on-quality` | Exit non-zero when thresholds fail |

## Outputs

Each run writes a timestamped directory under `outputs/`:

| File | Content |
|---|---|
| `raw-results.jsonl` | Query, expected docs, retrieval response, latency, answer, metrics |
| `ragas-results.csv` | Per-question RAGAS scores |
| `summary.json` | Aggregate metrics, query-type groups, threshold checks |
| `report.md` | Human-readable release report and failure analysis |

## CI Recommendation

Use a two-stage gate:

1. Every merge: `make ci` for `PUBLIC` visibility.
2. Nightly or pre-release: `make evaluate` for all visibility groups and RAGAS.

Do not use a passing retrieval-only run as the final answer-quality gate. A release
decision must include RAGAS `faithfulness`, `context_recall`, and `answer_relevancy`.

## Model Comparison

To compare embedding models:

1. Export the same golden dataset to an immutable directory.
2. Import and fully re-embed the corpus with model A.
3. Run `make evaluate`; rename or retain the output directory.
4. Repeat steps 2–3 for model B.
5. Compare `summary.json` and query-type group metrics.

Never mix vectors from different embedding models in the same `rag_chunk_embedding`
table. The current vector table is fixed at `vector(1536)`.
