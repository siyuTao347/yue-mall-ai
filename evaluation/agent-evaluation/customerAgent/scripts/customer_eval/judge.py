"""Optional chat-completions-compatible judge. It grades the actual API answer."""
from __future__ import annotations

import json
import os
import re

from .client import HttpClient
from .common import ROOT, check, digest, dumps, get_path


class Judge:
    def __init__(self, mode: str, redactor):
        self.mode = mode
        self.url = os.getenv("CUSTOMER_EVAL_JUDGE_URL", "")
        self.model = os.getenv("CUSTOMER_EVAL_JUDGE_MODEL", "")
        self.key = os.getenv("CUSTOMER_EVAL_JUDGE_API_KEY", "")
        self.rubric = (ROOT / "graders" / "semantic_rubric.md").read_text(encoding="utf-8")
        self.redactor = redactor

    @property
    def configured(self):
        return all([self.url, self.model, self.key])

    def score(self, turn: dict, record: dict, fixture: dict, conversation: list) -> tuple[dict, dict]:
        if self.mode == "off" or not self.configured:
            return check("semantic", None, "judge disabled or URL/MODEL/API_KEY incomplete"), {"status": "NOT_EVALUATED"}
        exp = turn["expected"]
        knowledge = get_path(fixture, exp["knowledge_ref"])
        evidence = {str(i): source for i, source in enumerate(knowledge.get("sources", []), 1)}
        candidate = record.get("response", {}).get("body", {}).get("data")
        if not isinstance(candidate, dict):
            return check("semantic", None, "no actual agent answer"), {"status": "NOT_EVALUATED"}
        request_data = self.redactor({"question": turn.get("content", ""), "history": conversation,
                                     "reference_points": knowledge.get("required_points", []),
                                     "evidence": evidence, "candidate": candidate})
        payload = {"model": self.model, "temperature": 0,
                   "messages": [{"role": "system", "content": self.rubric}, {"role": "user", "content": dumps(request_data)}]}
        if len(dumps(payload)) > 80000:
            return check("semantic", None, "judge input exceeds 80000 characters; no silent truncation"), {"status": "ERROR"}
        response = HttpClient(self.url, float(os.getenv("CUSTOMER_EVAL_JUDGE_TIMEOUT", "60"))).request_url(
            "POST", self.url, "Bearer " + self.key.removeprefix("Bearer "), payload)
        metadata = {"model": self.model, "rubric_hash": digest(self.rubric), "elapsed_ms": response["elapsed_ms"]}
        try:
            if response["http_status"] != 200 or response["error"]:
                raise ValueError("judge HTTP/transport error")
            body = response["body"]
            content = body["choices"][0]["message"]["content"]
            content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip())
            result = json.loads(content)
            if not isinstance(result, dict) or result.get("status") not in {"SCORED", "INSUFFICIENT_EVIDENCE"}:
                raise ValueError("invalid judge status")
            if result["status"] == "INSUFFICIENT_EVIDENCE":
                return check("semantic", None, "judge reports insufficient evidence"), dict(metadata, result=result, status=result["status"])
            for key in ["grounding", "coverage", "actionability", "clarity"]:
                if type(result.get(key)) is not int or result[key] not in {0, 1, 2}:
                    raise ValueError("invalid judge score")
            for key in ["evidence_refs", "unsupported_claims", "missing_requirements"]:
                if not isinstance(result.get(key), list) or not all(isinstance(x, str) for x in result[key]):
                    raise ValueError("invalid judge evidence lists")
            if any(ref not in evidence for ref in result["evidence_refs"]):
                raise ValueError("judge invented evidence reference")
            if not isinstance(result.get("reason"), str) or not result["reason"].strip():
                raise ValueError("judge reason missing")
            passed = result["grounding"] == 2 and result["coverage"] == 2 and not result["unsupported_claims"] and not result["missing_requirements"] and bool(result["evidence_refs"])
            return check("semantic", passed, result["reason"], actual=result), dict(metadata, status="SCORED", result=result, usage=body.get("usage"))
        except (KeyError, IndexError, TypeError, ValueError):
            return check("semantic", None, "judge HTTP/JSON/schema failure; not counted as a valid score"), dict(metadata, status="ERROR", http_status=response["http_status"])
