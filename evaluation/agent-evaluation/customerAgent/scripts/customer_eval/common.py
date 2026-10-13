"""File formats, strict configuration, interpolation and artifact redaction."""
from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
STATUSES = {"PASS", "FAIL", "NOT_EVALUATED", "NOT_APPLICABLE"}


def dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(dumps(value).encode()).hexdigest()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as exc:
            raise ValueError(f"{path.name}:{number}: invalid JSON") from exc
        if not isinstance(row, dict):
            raise ValueError(f"{path.name}:{number}: expected object")
        rows.append(row)
    return rows


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(dumps(row) + "\n" for row in rows), encoding="utf-8")


def load_env(path: Path | None) -> None:
    """Read literal KEY=value. Never execute shell, expand variables or overwrite env."""
    if path is None:
        return
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        key, sep, value = line.partition("=")
        if not sep or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", key.strip()):
            raise ValueError(f"env line {number}: expected KEY=value")
        value = value.strip()
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"env line {number}: unclosed quote")
            value = value[1:-1]
        os.environ.setdefault(key.strip(), value)


def get_path(value: Any, path: str) -> Any:
    for key in path.split("."):
        if not isinstance(value, dict) or key not in value:
            raise ValueError(f"missing fixture path: {path}")
        value = value[key]
    return value


PLACEHOLDER = re.compile(r"\{\{([A-Za-z0-9_.]+)\}\}")


def render(value: Any, fixture: dict) -> Any:
    if isinstance(value, dict):
        return {key: render(item, fixture) for key, item in value.items()}
    if isinstance(value, list):
        return [render(item, fixture) for item in value]
    if isinstance(value, str):
        exact = PLACEHOLDER.fullmatch(value)
        if exact:
            return get_path(fixture, exact.group(1))
        return PLACEHOLDER.sub(lambda m: str(get_path(fixture, m.group(1))), value)
    return value


def decode_columns(row: dict) -> dict:
    row = dict(row)
    for key, value in row.items():
        if key.endswith("_json") and isinstance(value, str):
            try:
                row[key] = json.loads(value)
            except ValueError as exc:
                raise ValueError(f"invalid trace JSON column: {key}") from exc
    return row


def outcome(checks: list[dict]) -> str:
    applicable = [c["status"] for c in checks if c["status"] != "NOT_APPLICABLE"]
    if "FAIL" in applicable:
        return "FAIL"
    if not applicable or "NOT_EVALUATED" in applicable:
        return "NOT_EVALUATED"
    return "PASS"


def check(name: str, passed: bool | None, reason: str = "", *, hard: bool = False,
          expected: Any = None, actual: Any = None) -> dict:
    return {"check_id": name, "status": "NOT_EVALUATED" if passed is None else "PASS" if passed else "FAIL",
            "reason": reason, "hard": hard, "expected": expected, "actual": actual}


class Redactor:
    """Score original values first; redact only at artifact/judge boundaries."""

    PRIVATE_KEYS = re.compile(r"^(authorization|api[_-]?key|password|card[_-]?secret|access[_-]?token|refresh[_-]?token)$", re.I)

    def __init__(self, secrets: list[str] = ()):
        self.secrets = sorted({s for s in secrets if s}, key=len, reverse=True)

    def __call__(self, value: Any) -> Any:
        if isinstance(value, dict):
            return {k: "[REDACTED]" if self.PRIVATE_KEYS.fullmatch(k) else self(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self(v) for v in value]
        if isinstance(value, str):
            for secret in self.secrets:
                value = value.replace(secret, "[REDACTED]")
            value = re.sub(r"(?i)bearer\s+[a-z0-9_.-]+", "Bearer [REDACTED]", value)
            value = re.sub(r"eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+", "[JWT REDACTED]", value)
            value = re.sub(r"(?i)((?:密码|卡密|支付凭证|password|api[_-]?key|token)\s*[:：=]\s*)[^\s，。\"}]+", r"\1[REDACTED]", value)
            return value
        return value
