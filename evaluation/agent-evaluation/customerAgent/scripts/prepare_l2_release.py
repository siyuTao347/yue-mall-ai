#!/usr/bin/env python3
"""Validate an independent L2 set and export the existing release API request body.

This script never builds, activates, revokes or mutates a release.
"""
import argparse
import re
import unicodedata
from pathlib import Path

from customer_eval.common import ROOT, read_json, read_jsonl, write_json


def normalize(text):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).strip().lower()


def prepare(rows, intent_codes, training=()):
    if not 3 <= len(rows) <= 200:
        raise ValueError("release API accepts 3..200 cases; explicitly select a covering subset if larger")
    expected = set(intent_codes) | {"OTHER"}
    if {r["expectedIntent"] for r in rows} != expected:
        raise ValueError("dataset must cover exactly the target intent set plus OTHER")
    if len(expected) < 3:
        raise ValueError("at least two business intents required")
    seen, seeds = set(), {normalize(s) for s in training}
    for row in rows:
        q = row["query"]
        if not isinstance(q, str) or not q.strip() or len(q.encode("utf-16-le")) // 2 > 512:
            raise ValueError("invalid/overlength release question")
        norm = normalize(q)
        if norm in seen or norm in seeds:
            raise ValueError(f"duplicate/training overlap: {row.get('case_id', 'unknown')}")
        seen.add(norm)
    return {"evaluations": [{"query": r["query"], "expectedIntent": r["expectedIntent"]} for r in rows]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset", type=Path, default=ROOT / "datasets/l2-calibration.jsonl")
    p.add_argument("--intent-codes", nargs="+", help="exact enabled business codes of the target release")
    p.add_argument("--training-examples", type=Path, help="JSON array of all target release example strings")
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    rows = read_jsonl(args.dataset)
    codes = args.intent_codes or sorted({r["expectedIntent"] for r in rows} - {"OTHER"})
    training = read_json(args.training_examples) if args.training_examples else []
    if not isinstance(training, list) or not all(isinstance(s, str) for s in training):
        raise ValueError("training examples must be a JSON array of strings")
    if args.output.exists():
        raise ValueError("output already exists")
    write_json(args.output, prepare(rows, codes, training))
    print(f"Wrote {len(rows)} evaluations to {args.output}; training overlap check: {'performed' if args.training_examples else 'NOT_EVALUATED'}")
    print("No release API called. These are calibration candidates, not a validated READY release.")


if __name__ == "__main__":
    main()
