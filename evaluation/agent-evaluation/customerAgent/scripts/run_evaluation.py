#!/usr/bin/env python3
"""Run customerAgent API cases; --validate/--dry-run do not access any service."""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import os
import subprocess
import sys
import uuid
from pathlib import Path

from customer_eval.client import HttpClient
from customer_eval.common import ROOT, Redactor, digest, dumps, load_env, read_json, read_jsonl, write_json, write_jsonl
from customer_eval.dataset import validate
from customer_eval.judge import Judge
from customer_eval.report import summarize, write_report
from customer_eval.runner import Runner

SMOKE = {"GREETING-001", "GREETING-009", "ORDER-001", "AFTER_SALE-001", "RULES-001", "MULTI-001", "HANDOFF-001", "SECURITY-013", "OTHER-001", "OTHER-017"}


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--env-file", type=Path)
    p.add_argument("--dataset", type=Path, default=ROOT / "datasets/functional.jsonl")
    p.add_argument("--fixtures", type=Path, default=ROOT / "fixtures/template.json")
    p.add_argument("--gates", type=Path, default=ROOT / "config/gates.json")
    p.add_argument("--base-url")
    p.add_argument("--mode", choices=["mock", "live"])
    p.add_argument("--suite", choices=["smoke", "all"], default="smoke")
    p.add_argument("--split", choices=["dev", "holdout", "all"], default="all")
    p.add_argument("--category", action="append", default=[])
    p.add_argument("--case-id", action="append", default=[])
    p.add_argument("--limit", type=int)
    p.add_argument("--trials", type=int, default=1)
    p.add_argument("--workers", type=int, default=1)
    p.add_argument("--interval", type=float, help="minimum per-identity message interval, seconds")
    p.add_argument("--judge", choices=["auto", "off", "required"], default="auto")
    p.add_argument("--validate", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--output", type=Path, help="new directory; never overwrites a prior run")
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    load_env(args.env_file)
    cases, fixture, gates = read_jsonl(args.dataset), read_json(args.fixtures), read_json(args.gates)
    stats = validate(cases, fixture)
    if args.validate:
        print(dumps({"validation": "PASS", **stats, "note": "format only; fixture readiness and live quality not evaluated"}))
        return 0
    selected = [c for c in cases if (args.suite == "all" or c["case_id"] in SMOKE or args.case_id)
                and (args.split == "all" or c["split"] == args.split)
                and (not args.category or c["category"] in args.category)
                and (not args.case_id or c["case_id"] in args.case_id)]
    if args.limit is not None:
        if args.limit < 1:
            raise ValueError("--limit must be positive")
        selected = selected[:args.limit]
    if not selected:
        raise ValueError("no cases selected")
    if args.trials < 1 or not 1 <= args.workers <= 16:
        raise ValueError("trials must be positive and workers 1..16")
    if args.case_id and set(args.case_id) - {c["case_id"] for c in cases}:
        raise ValueError("unknown --case-id")
    mode = args.mode or os.getenv("CUSTOMER_EVAL_MODE", "mock")
    if mode not in {"mock", "live"}:
        raise ValueError("invalid mode")
    if args.dry_run:
        print(dumps({"mode": mode, "cases": len(selected), "trials": len(selected) * args.trials,
                     "case_ids": [c["case_id"] for c in selected], "network_requests": 0,
                     "required_fixtures": sorted({r for c in selected for r in c.get("requires", []) + (c.get("requires_live", []) if mode == "live" else [])})}))
        return 0
    if args.workers > 1:
        unknown = [name for name, actor in fixture["actors"].items() if name != "admin_eval"
                   and os.getenv(actor["token_env"]) and not actor.get("user_id")]
        if unknown:
            raise ValueError("parallel runs require user_id for configured actors: " + ", ".join(unknown))
    interval = args.interval if args.interval is not None else float(os.getenv("CUSTOMER_EVAL_INTERVAL", "15.1"))
    if interval < 0:
        raise ValueError("interval cannot be negative")
    timeout = float(os.getenv("CUSTOMER_EVAL_HTTP_TIMEOUT", "100"))
    if timeout <= 0:
        raise ValueError("HTTP timeout must be positive")
    tokens = [os.getenv(a["token_env"], "") for a in fixture["actors"].values()]
    redactor = Redactor(tokens + [os.getenv("CUSTOMER_EVAL_JUDGE_API_KEY", "")] + fixture.get("forbidden_literals", []))
    judge = Judge(args.judge, redactor)
    if args.judge == "required" and not judge.configured:
        raise ValueError("required judge needs CUSTOMER_EVAL_JUDGE_URL/MODEL/API_KEY")
    client = HttpClient(args.base_url or os.getenv("CUSTOMER_EVAL_BASE_URL", "http://127.0.0.1:8085"), timeout)
    runner = Runner(client, fixture, mode, judge, interval)
    folder = args.output or ROOT / "outputs" / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True, exist_ok=False)
    git = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    implementation = {str(p.relative_to(ROOT)): p.read_text(encoding="utf-8") for p in (ROOT / "scripts").rglob("*.py")}
    manifest = {"schema_version": 1, "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                "git_commit": git.stdout.strip() if git.returncode == 0 else None,
                "dataset_hash": digest(cases), "selection_hash": digest(selected), "fixture_hash": digest(fixture), "harness_hash": digest(implementation),
                "gates": gates, "mode": mode, "base_url": client.base_url, "trials_per_case": args.trials,
                "workers": args.workers, "interval": interval, "timeout": timeout, "judge_mode": args.judge,
                "judge_model": judge.model or None, "judge_configured": judge.configured,
                "judge_rubric_hash": digest(judge.rubric),
                "fixture_manifest": fixture.get("manifest", {}), "case_ids": [c["case_id"] for c in selected],
                "automatic_request_retries": 0, "raw_artifacts_are_redacted": True}
    write_json(folder / "manifest.json", redactor(manifest))
    results = []
    tasks = [(case, i) for i in range(1, args.trials + 1) for case in selected]
    # Append after every finished trial so an interrupted evaluation keeps evidence.
    with (folder / "raw-results.jsonl").open("x", encoding="utf-8") as stream:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = [executor.submit(runner.run, case, i) for case, i in tasks]
            for future in concurrent.futures.as_completed(futures):
                result = redactor(future.result())
                results.append(result)
                stream.write(dumps(result) + "\n"); stream.flush()
                print(f"[{len(results)}/{len(tasks)}] {result['case_id']} #{result['trial']}: {result['status']}", flush=True)
    results.sort(key=lambda r: (r["case_id"], r["trial"]))
    write_jsonl(folder / "checks.jsonl", [{"case_id": t["case_id"], "trial": t["trial"], **c} for t in results for c in t["checks"]])
    write_jsonl(folder / "judge-results.jsonl", [{"case_id": t["case_id"], "trial": t["trial"], "turn": row["turn"], **row["judge"]} for t in results for row in t["turns"] if "judge" in row])
    summary = summarize(results, gates, args.trials)
    write_report(folder, summary, results, manifest)
    print(f"Report: {folder / 'report.md'}\nSelected scope: {summary['status']}; full release: NOT_EVALUATED")
    return {"PASS": 0, "FAIL": 1, "NOT_EVALUATED": 2}[summary["status"]]


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as exc:
        print(f"Configuration/input error: {exc}", file=sys.stderr)
        raise SystemExit(2)
