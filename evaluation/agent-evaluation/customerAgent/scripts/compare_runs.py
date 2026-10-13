#!/usr/bin/env python3
"""Paired comparison of runs with the same selected cases, fixtures and trial count."""
import argparse
from collections import Counter
from pathlib import Path

from customer_eval.common import read_json, read_jsonl, write_json


def compare(base, candidate):
    left, right = read_json(base / "manifest.json"), read_json(candidate / "manifest.json")
    for key in ["dataset_hash", "selection_hash", "fixture_hash", "trials_per_case", "mode", "judge_model", "judge_mode", "judge_rubric_hash", "gates"]:
        if left.get(key) != right.get(key):
            raise ValueError(f"incomparable runs: {key} differs")
    def keyed(folder):
        rows = read_jsonl(folder / "raw-results.jsonl")
        result = {(r["case_id"], r["trial"]): r for r in rows}
        if len(result) != len(rows):
            raise ValueError("duplicate trial in raw artifact")
        return result
    a, b = keyed(base), keyed(candidate)
    if a.keys() != b.keys():
        raise ValueError("trial sets differ (possibly interrupted); do not compare a truncated run")
    if len(a) != len(left["case_ids"]) * left["trials_per_case"]:
        raise ValueError("both runs are incomplete")
    pairs, changes = [], Counter()
    for key in sorted(a):
        before, after = a[key]["status"], b[key]["status"]
        if "NOT_EVALUATED" in {before, after}:
            kind = "unscored"
        elif before == after:
            kind = "both_pass" if before == "PASS" else "both_fail"
        else:
            kind = "regression" if before == "PASS" else "improvement"
        changes[kind] += 1
        pairs.append({"case_id": key[0], "trial": key[1], "baseline": before, "candidate": after, "change": kind})
    return {"counts": dict(changes), "pairs": pairs, "note": "Paired descriptive comparison; no significance claim. Review regressions and group repeated trials by case."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("baseline", type=Path); p.add_argument("candidate", type=Path)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    if args.output.exists():
        raise ValueError("output already exists")
    result = compare(args.baseline, args.candidate)
    write_json(args.output, result)
    print(result["counts"])


if __name__ == "__main__":
    main()
