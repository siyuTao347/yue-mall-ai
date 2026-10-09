#!/usr/bin/env python3
"""Build a deterministic golden set from doc/data/RAG source documents."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[3]
SOURCE_DIR = ROOT / "doc" / "data" / "RAG"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "datasets" / "golden-questions.jsonl"
EXTENDED_FILE = Path(__file__).resolve().parents[1] / "datasets" / "extended-golden-questions.jsonl"


def load_documents() -> list[dict]:
    documents = []
    for path in sorted(SOURCE_DIR.glob("PROD-*.md")):
        text = path.read_text(encoding="utf-8")
        if not text.startswith("---\n"):
            raise ValueError(f"{path} has no YAML front matter")
        end = text.find("\n---", 4)
        if end < 0:
            raise ValueError(f"{path} has malformed YAML front matter")
        metadata = yaml.safe_load(text[4:end])
        metadata["source_path"] = f"doc/data/RAG/{path.name}"
        documents.append(metadata)
    if not documents:
        raise ValueError(f"No source documents found in {SOURCE_DIR}")
    return documents


def visibility(metadata: dict) -> str:
    risk = str(metadata.get("risk_level", "")).upper()
    visible = (
        str(metadata.get("audit_status", "")).upper() == "APPROVED"
        and str(metadata.get("shelf_status", "")).upper() == "ON_SHELF"
        and risk not in {"CRITICAL", "MANUAL_REVIEW"}
    )
    return "PUBLIC" if visible else "INTERNAL"


def base_record(metadata: dict, query_type: str, query: str, ground_truth: str) -> dict:
    return {
        "id": f"{metadata['doc_id']}-{query_type.upper()}",
        "query_type": query_type,
        "query": query,
        "expected_doc_nos": [metadata["doc_id"]],
        "expected_titles": [metadata["title"]],
        "ground_truth": ground_truth,
        "visibility": visibility(metadata),
        "source_path": metadata["source_path"],
    }


def product_records(metadata: dict) -> list[dict]:
    title = metadata["title"]
    merchant = metadata["merchant"]
    price = f"{float(metadata['price_cny']):.2f}"
    stock = int(metadata["stock"])
    delivery_mode = str(metadata["delivery_mode"]).upper()
    delivery = "自动发卡" if delivery_mode == "AUTO_CARD" else "人工交付"

    return [
        base_record(
            metadata,
            "exact",
            f"购买「{title}」前，我想确认价格、库存、交付方式和商家。",
            f"{title}的价格为 {price} 元，库存 {stock} 件，交付方式为{delivery}，商家为 {merchant}。",
        ),
        base_record(
            metadata,
            "attribute",
            f"{title}的交付方式是什么？",
            f"{title}的交付方式为{delivery}。",
        ),
        base_record(
            metadata,
            "semantic",
            f"{merchant}店里那件「{title}」现在卖多少钱？还剩几件库存？",
            f"{merchant}出售的{title}价格为 {price} 元，库存 {stock} 件。",
        ),
    ]


def write_jsonl(records: list[dict], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    print(f"Wrote {len(records)} records to {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    records = [record for metadata in load_documents() for record in product_records(metadata)]
    if EXTENDED_FILE.exists():
        with EXTENDED_FILE.open(encoding="utf-8") as handle:
            records.extend(json.loads(line) for line in handle if line.strip())

    write_jsonl(records, args.output)


if __name__ == "__main__":
    main()
