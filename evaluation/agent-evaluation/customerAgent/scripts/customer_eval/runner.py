"""Real customer HTTP conversation execution with per-user isolation."""
from __future__ import annotations

import hashlib
import os
import threading
import uuid
from contextlib import ExitStack
from urllib.parse import quote

from .client import Pacer, data
from .common import check, decode_columns, get_path, outcome, render
from .grading import evaluate_turn


class Runner:
    def __init__(self, client, fixture, mode, judge, interval=15.1):
        self.client, self.fixture, self.mode, self.judge = client, fixture, mode, judge
        self.pacer = Pacer(interval)
        self.locks = {}
        self.lock = threading.Lock()

    def token(self, actor):
        if actor == "anonymous":
            return ""
        if actor == "invalid":
            return "Bearer invalid-evaluation-jwt"
        return os.getenv(self.fixture["actors"][actor]["token_env"], "")

    def identity(self, actor):
        record = self.fixture["actors"].get(actor, {})
        return "user:" + str(record["user_id"]) if record.get("user_id") else "token:" + hashlib.sha256(self.token(actor).encode()).hexdigest()

    def run(self, raw_case, trial):
        result = {"case_id": raw_case["case_id"], "category": raw_case["category"], "split": raw_case["split"],
                  "trial": trial, "status": "NOT_EVALUATED", "checks": [], "turns": []}
        actor_refs = {raw_case["actor_ref"]} | {t.get("actor_ref", raw_case["actor_ref"]) for t in raw_case["turns"]}
        missing = [f"token:{a}" for a in actor_refs if a not in {"anonymous", "invalid"} and not self.token(a)]
        requirements = raw_case.get("requires", []) + (raw_case.get("requires_live", []) if self.mode == "live" else [])
        for ref in requirements:
            if not get_path(self.fixture, ref).get("ready"):
                missing.append(ref)
        if missing:
            result["checks"] = [check("prerequisites", None, "missing/not ready: " + ", ".join(missing))]
            return result
        case = render(raw_case, self.fixture)
        # Entire conversations using the same identity are serialized, including aliases.
        identities = sorted({self.identity(a) for a in actor_refs if a not in {"anonymous", "invalid"}})
        with self.lock:
            locks = [self.locks.setdefault(i, threading.Lock()) for i in identities]
        with ExitStack() as stack:
            for lock in locks:
                stack.enter_context(lock)
            try:
                self._conversation(case, result)
            except (ValueError, KeyError, TypeError, IndexError, AttributeError):
                result["checks"].append(check("harness_or_collection", None, "unexpected response/configuration shape; inspect recorded evidence"))
        result["status"] = outcome(result["checks"])
        return result

    def _conversation(self, case, result):
        session = None
        aliases, requests, conversation = {}, [], []
        for number, turn in enumerate(case["turns"], 1):
            op, actor = turn.get("op", "message"), turn.get("actor_ref", case["actor_ref"])
            token = self.token(actor)
            if op in {"message", "replay", "session", "close"} and session is None:
                setup = self.client.request("POST", "/api/agent/sessions", self.token(case["actor_ref"]))
                try:
                    session = data(setup)["sessionNo"]
                except (ValueError, TypeError, KeyError):
                    result["setup_response"] = setup
                    result["checks"].append(check("session_setup", False, "could not create the test user's session"))
                    return
            path_session = quote(str(session), safe="")
            payload, method = None, "GET"
            if op in {"message", "replay"}:
                if op == "replay":
                    previous = requests[turn["replay_turn"] - 1]
                    payload = dict(previous)
                    if "content" in turn:
                        payload["content"] = turn["content"]
                else:
                    payload = {"content": turn["content"], "clientMessageId": turn.get("client_message_id", "eval-" + uuid.uuid4().hex)}
                method, path = "POST", f"/api/agent/sessions/{path_session}/messages"
                self.pacer.wait(self.identity(actor))
            elif op == "create_session":
                method, path = "POST", "/api/agent/sessions"
            elif op == "session":
                path = f"/api/agent/sessions/{path_session}"
            elif op == "close":
                method, path = "POST", f"/api/agent/sessions/{path_session}/close"
            elif op in {"feedback", "admin_tools", "handoff_process", "handoff_resolve"}:
                previous = data(result["turns"][turn.get("from_turn", 1) - 1]["response"])
                if op == "feedback":
                    method, path = "POST", "/api/agent/runs/" + quote(previous["runNo"], safe="") + "/feedback"
                    payload = turn.get("body", {"rating": 5, "comment": "evaluation fixture"})
                elif op == "admin_tools":
                    path = "/api/admin/agent/runs/" + quote(previous["runNo"], safe="") + "/tool-calls"
                else:
                    suffix = "process" if op == "handoff_process" else "resolve"
                    method, path = "POST", "/api/admin/agent/handoffs/" + quote(previous["handoffNo"], safe="") + "/" + suffix
                    payload = None if suffix == "process" else {"resolution": "已完成隔离环境测评工单验证"}
            else:
                path = {"admin_runs": "/api/admin/agent/runs", "admin_handoffs": "/api/admin/agent/handoffs", "admin_releases": "/api/admin/agent/intent-releases"}[op]
            response = self.client.request(method, path, token, payload, turn.get("headers"))
            requests.append(payload)
            record = {"turn": number, "op": op, "actor_ref": actor, "request": {"method": method, "path": path, "body": payload},
                      "expected": turn["expected"],
                      "response": response, "trace": None, "tools": None, "handoff": None, "collection_errors": []}
            if op == "create_session" and response.get("http_status") == 200:
                try:
                    session = data(response)["sessionNo"]
                except (KeyError, ValueError, TypeError):
                    pass
            if turn["expected"].get("agent_response"):
                self._collect(record)
            record["checks"] = evaluate_turn(turn, record, self.fixture, aliases, self.mode, result["turns"])
            if turn["expected"].get("judge") and self.mode == "live":
                judgment, record["judge"] = self.judge.score(turn, record, self.fixture, conversation)
                record["checks"].append(judgment)
            result["turns"].append(record)
            result["checks"].extend(dict(c, check_id=f"turn{number}:" + c["check_id"]) for c in record["checks"])
            body = response.get("body") or {}
            public = body.get("data") if isinstance(body, dict) else None
            conversation.append({"user": turn.get("content", ""), "answer": public.get("answer") if isinstance(public, dict) else None})

    def _collect(self, record):
        try:
            public = data(record["response"])
            run = quote(public["runNo"], safe="")
        except (ValueError, KeyError, TypeError):
            return
        admin = self.token("admin_eval")
        if not admin:
            record["collection_errors"].append("ADMIN_TOKEN missing")
            return
        for key, path in [("trace", "/api/admin/agent/runs/" + run), ("tools", "/api/admin/agent/runs/" + run + "/tool-calls")]:
            try:
                value = data(self.client.request("GET", path, admin))
                if key == "trace" and isinstance(value, dict):
                    record[key] = decode_columns(value)
                elif key == "tools" and isinstance(value, list) and all(isinstance(v, dict) for v in value):
                    decoded = [decode_columns(v) for v in value]
                    if not all(isinstance(v.get("input_json"), dict) and isinstance(v["input_json"].get("arguments"), dict) for v in decoded):
                        raise ValueError("invalid tool input shape")
                    record[key] = decoded
                else:
                    raise ValueError("invalid collection shape")
            except (ValueError, TypeError):
                record["collection_errors"].append(key + " unavailable or malformed")
        if public.get("handoffRequired") and public.get("handoffNo"):
            try:
                record["handoff"] = decode_columns(data(self.client.request("GET", "/api/admin/agent/handoffs/" + quote(public["handoffNo"], safe=""), admin)))
            except (ValueError, TypeError):
                record["collection_errors"].append("handoff unavailable or malformed")
