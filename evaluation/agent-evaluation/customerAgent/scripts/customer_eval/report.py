"""Scope-aware reports, denominators, repeats and regression comparisons."""
from __future__ import annotations

import math
from collections import Counter, defaultdict

from .common import outcome, write_json
from .grading import subset


def master_of(trace):
    value = trace.get("master_result_json") if isinstance(trace, dict) else None
    return value if isinstance(value, dict) else {}


def instance_metrics(trials):
    """Maximum one-to-one matching for post-policy intent + business-object slots."""
    tp, fp, fn = Counter(), Counter(), Counter()
    applicable = scored = exact = 0
    for trial in trials:
        for turn in trial.get("turns", []):
            gold = turn.get("expected", {}).get("instances")
            if gold is None or turn.get("op") == "replay":
                continue
            applicable += 1
            actual = master_of(turn.get("trace")).get("intents")
            if not isinstance(actual, list) or len(actual) > 2 or not all(isinstance(a, dict) for a in actual):
                continue
            scored += 1
            def best(index, used):
                if index == len(gold):
                    return []
                winner = best(index + 1, used)
                g = gold[index]
                for j, a in enumerate(actual):
                    if j in used or a.get("code") not in g.get("codes", [g.get("code")]):
                        continue
                    if not subset(g.get("slots", {}), a.get("slots", {})):
                        continue
                    if any(a.get("slots", {}).get(key) is not None for key in g.get("missing_slots", [])):
                        continue
                    candidate = [(index, j)] + best(index + 1, used | {j})
                    if len(candidate) > len(winner):
                        winner = candidate
                return winner
            matching = best(0, set())
            good_gold, good_actual = {i for i, _ in matching}, {j for _, j in matching}
            exact += len(matching) == len(gold) == len(actual)
            for _, j in matching:
                tp[actual[j]["code"]] += 1
            for j, a in enumerate(actual):
                if j not in good_actual:
                    code = a.get("code")
                    fp[code if isinstance(code, str) and code else "UNKNOWN"] += 1
            for i, g in enumerate(gold):
                if i not in good_gold:
                    fn[g.get("code", "|".join(g.get("codes", [])))] += 1
    def scores(t, p, n):
        return {"tp": t, "fp": p, "fn": n, "precision": t / (t + p) if t + p else None,
                "recall": t / (t + n) if t + n else None, "f1": 2 * t / (2 * t + p + n) if 2 * t + p + n else None}
    per_intent = {code: scores(tp[code], fp[code], fn[code]) for code in sorted(tp.keys() | fp.keys() | fn.keys())}
    f1s = [v["f1"] for v in per_intent.values() if v["f1"] is not None]
    return {"definition": "post-policy code + grounded slots, one-to-one instances; not raw LLM classification",
            "applicable_turns": applicable, "scored_turns": scored, "coverage": scored / applicable if applicable else None,
            "exact_match": exact / applicable if applicable else None,
            "micro": scores(sum(tp.values()), sum(fp.values()), sum(fn.values())),
            "macro_f1": sum(f1s) / len(f1s) if f1s else None, "per_intent": per_intent}


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def summarize(trials: list[dict], gates: dict, requested_trials: int) -> dict:
    counts = Counter(t["status"] for t in trials)
    all_checks = [c for t in trials for c in t["checks"]]
    applicable = [c for c in all_checks if c["status"] != "NOT_APPLICABLE"]
    measured = [c for c in applicable if c["status"] in {"PASS", "FAIL"}]
    hard_failures = [dict(case_id=t["case_id"], trial=t["trial"], **c) for t in trials for c in t["checks"] if c.get("hard") and c["status"] == "FAIL"]
    by_category = defaultdict(Counter)
    by_case = defaultdict(list)
    sources = Counter()
    providers = Counter()
    latency = []
    successful_latency = []
    per_source_latency = defaultdict(list)
    runs = {}
    semantic = []
    for trial in trials:
        by_category[trial["category"]][trial["status"]] += 1
        by_case[trial["case_id"]].append(trial)
        for c in trial["checks"]:
            if c["check_id"].endswith("semantic"):
                semantic.append(c)
        for turn in trial.get("turns", []):
            if turn.get("op", "message") not in {"message", "replay"}:
                continue
            response = turn.get("response", {})
            ms = response.get("elapsed_ms")
            trace = turn.get("trace") or {}
            recognition = master_of(trace).get("recognition")
            source = recognition.get("source") if isinstance(recognition, dict) else None
            source = source if isinstance(source, str) and source else "UNKNOWN"
            if ms is not None:
                latency.append(ms)
                per_source_latency[source].append(ms)
                if outcome(turn.get("checks", [])) == "PASS":
                    successful_latency.append(ms)
            if turn.get("op") == "replay":
                continue  # replay latency is measured, cached tokens are not billed again
            if trace.get("run_no"):
                runs[trace["run_no"]] = trace
                sources[source] += 1
                providers[trace.get("provider", "UNKNOWN")] += 1
    repetition = {"k": requested_trials, "cases": len(by_case),
                  "at_least_one_pass": sum(any(t["status"] == "PASS" for t in ts) for ts in by_case.values()),
                  "all_k_pass": sum(len(ts) == requested_trials and all(t["status"] == "PASS" for t in ts) for ts in by_case.values())}
    repetitions_complete = all(len(ts) == requested_trials for ts in by_case.values())
    success_rate = counts["PASS"] / len(trials) if trials else None
    gate_results = []
    def gate(name, status, actual, threshold):
        gate_results.append({"check_id": name, "status": status, "actual": actual, "expected": threshold})
    gate("hard_failures", "FAIL" if hard_failures else "PASS", len(hard_failures), 0)
    gate("complete_execution", "PASS" if trials and not counts["NOT_EVALUATED"] and repetitions_complete else "NOT_EVALUATED", dict(counts), "all applicable trials evaluated")
    # Missing evidence is NOT_EVALUATED, not a misleading low-quality model score.
    rate_state = "NOT_EVALUATED" if success_rate is None or counts["NOT_EVALUATED"] else "PASS" if success_rate >= gates["task_success_rate"] else "FAIL"
    gate("task_success_rate", rate_state, success_rate, gates["task_success_rate"])
    for category, group in sorted(by_category.items()):
        rate = group["PASS"] / sum(group.values())
        gate("category:" + category, "NOT_EVALUATED" if group["NOT_EVALUATED"] else "PASS" if rate >= gates["category_success_rate"] else "FAIL", rate, gates["category_success_rate"])
    if requested_trials > 1 and by_case:
        rate = repetition["all_k_pass"] / repetition["cases"]
        gate("all_k_pass", "NOT_EVALUATED" if counts["NOT_EVALUATED"] or not repetitions_complete else "PASS" if rate >= gates["all_k_pass_rate"] else "FAIL", rate, gates["all_k_pass_rate"])
    valid_semantic = [c for c in semantic if c["status"] in {"PASS", "FAIL"}]
    token_rows = [r for r in runs.values() if type(r.get("input_tokens")) is int and type(r.get("output_tokens")) is int]
    return {"scope": "selected API functional cases; not a full production release certification",
            "status": outcome(gate_results), "release_status": "NOT_EVALUATED",
            "release_gaps": ["T3 real database/HTTP cancellation and fault injection", "model-call probes and usage completeness", "business-table before/after snapshots", "human gold/judge review", "representative load and false-positive confidence intervals"],
            "planned_trials": len(trials), "counts": {s: counts[s] for s in ["PASS", "FAIL", "NOT_EVALUATED"]},
            "task_success_rate": success_rate, "hard_failures": hard_failures,
            "check_coverage": len(measured) / len(applicable) if applicable else None,
            "categories": {k: dict(v) for k, v in sorted(by_category.items())}, "recognition_sources": dict(sources),
            "providers": dict(providers), "repetition": repetition, "gates": gate_results,
            "routed_instance_metrics": instance_metrics(trials),
            "semantic": {"applicable": len(semantic), "scored": len(valid_semantic), "passed": sum(c["status"] == "PASS" for c in semantic),
                         "coverage": len(valid_semantic) / len(semantic) if semantic else None},
            "latency_ms": {"all_count": len(latency), "all_p50": percentile(latency, .5), "all_p95": percentile(latency, .95),
                           "success_count": len(successful_latency), "success_p95": percentile(successful_latency, .95),
                           "by_source": {k: {"count": len(v), "p95": percentile(v, .95)} for k, v in per_source_latency.items()}},
            "tokens": {"unique_runs": len(runs), "rows_with_reported_chat_tokens": len(token_rows),
                       "reported_input": sum(r["input_tokens"] for r in token_rows), "reported_output": sum(r["output_tokens"] for r in token_rows),
                       "usage_verified": False, "cost": None, "note": "Reported tokens only. Zero does not prove no model call; embedding/judge billing separate."}}


def write_report(folder, summary, trials, manifest):
    write_json(folder / "summary.json", summary)
    lines = ["# customerAgent 测评报告", "", f"- 当前选定范围：**{summary['status']}**",
             "- 完整发布结论：**NOT_EVALUATED**（参见 summary.json 的 release_gaps）",
             f"- 数据集 hash：`{manifest['dataset_hash']}`", f"- 计划 trial：{len(trials)}；状态：{summary['counts']}",
             f"- 完整成功率：{summary['task_success_rate']}；检查覆盖率：{summary['check_coverage']}",
             f"- 硬性失败：{len(summary['hard_failures'])}", "", "## 分组", "", "| 类别 | PASS | FAIL | NOT_EVALUATED |", "|---|---:|---:|---:|"]
    for category, counts in summary["categories"].items():
        lines.append(f"| {category} | {counts.get('PASS', 0)} | {counts.get('FAIL', 0)} | {counts.get('NOT_EVALUATED', 0)} |")
    lines += ["", "## 失败与缺失证据", ""]
    for trial in trials:
        if trial["status"] == "PASS":
            continue
        lines.append(f"### {trial['case_id']} / trial {trial['trial']} — {trial['status']}")
        lines.append("")
        for c in trial["checks"]:
            if c["status"] not in {"PASS", "NOT_APPLICABLE"}:
                reason = str(c.get("reason", "")).replace("\n", " ")
                lines.append(f"- `{c['check_id']}`：{c['status']} {reason}")
        lines.append("")
    lines += ["## 解读限制", "", "Mock 只验证契约。未执行、缺证据、裁判错误不计通过。性能为本次选定样本，不代表生产容量。",
              "业务事实和引用以独立 fixture 为依据；正式发布还需人工审核 gold、故障注入和性能专项。", ""]
    (folder / "report.md").write_text("\n".join(lines), encoding="utf-8")
