"""Fail-closed dataset validation; schemas are versioned with the harness."""
from .common import get_path, render
from .grading import DISPUTE_FIELDS, ORDER_FIELDS, ROUTES

OPS = {"message", "replay", "session", "close", "create_session", "feedback", "admin_runs", "admin_tools", "admin_handoffs", "admin_releases", "handoff_process", "handoff_resolve"}
EXPECTED = {"http_status", "api_codes", "error_codes", "no_business_facts", "answer_regex", "forbidden_regex", "forbidden_literals", "data_subset",
            "same_as_turn", "session_status", "contains_run_from_turn", "agent_response", "response_errors", "handoff", "sources", "not_sources",
            "instances", "tool_count", "tools", "forbidden_tools", "no_reexecute", "choices_owned", "min_choices", "knowledge_ref", "knowledge_empty", "required_probes", "judge"}
INSTANCE = {"alias", "code", "codes", "slots", "state", "missing_slots", "resume", "slot_sources", "allow_pending_without_result", "error_codes", "fact_refs", "timeline_ref", "answer_regex", "tools"}


def validate(cases, fixture):
    if not cases:
        raise ValueError("dataset is empty")
    for kind, fields in [("orders", ORDER_FIELDS), ("disputes", DISPUTE_FIELDS)]:
        for name, item in fixture.get(kind, {}).items():
            if item.get("ready") and (not fields.issubset(item) or not item.get("owners")
                    or any(a not in fixture["actors"] for a in item["owners"])):
                raise ValueError(f"{kind}.{name}: ready fixture needs full DTO and valid owners")
    for name, item in fixture.get("knowledge", {}).items():
        if item.get("ready") and (not item.get("sources") or not item.get("required_points") or not item.get("expected_doc_nos")):
            raise ValueError(f"knowledge.{name}: ready gold needs source snapshots and reference points")
    ids, families, questions = set(), {}, set()
    for case in cases:
        cid = case["case_id"]
        if cid in ids:
            raise ValueError(f"duplicate case_id: {cid}")
        ids.add(cid)
        actors = {case["actor_ref"]} | {t.get("actor_ref", case["actor_ref"]) for t in case["turns"]}
        if actors - (set(fixture["actors"]) | {"anonymous", "invalid"}):
            raise ValueError(f"{cid}: unknown actor_ref")
        if case["split"] not in {"dev", "holdout"}:
            raise ValueError(f"{cid}: invalid split")
        family = case["family"]
        if family in families and families[family] != case["split"]:
            raise ValueError(f"family crosses splits: {family}")
        families[family] = case["split"]
        for ref in case.get("requires", []) + case.get("requires_live", []):
            get_path(fixture, ref)
        if not case.get("turns"):
            raise ValueError(f"{cid}: no turns")
        aliases = set()
        rendered = render(case, fixture)
        for number, turn in enumerate(rendered["turns"], 1):
            if set(turn) - {"op", "content", "expected", "actor_ref", "headers", "client_message_id", "replay_turn", "body", "from_turn"}:
                raise ValueError(f"{cid}: unsupported turn field")
            if turn.get("op", "message") not in OPS:
                raise ValueError(f"{cid}: unknown operation")
            exp = turn.get("expected", {})
            unknown = set(exp) - EXPECTED
            if unknown or not exp:
                raise ValueError(f"{cid}: unsupported/missing expectations {unknown}")
            if exp.get("judge") and not exp.get("knowledge_ref"):
                raise ValueError(f"{cid}: judge requires independent knowledge_ref")
            if "knowledge_ref" in exp:
                get_path(fixture, exp["knowledge_ref"])
            for key in ["same_as_turn", "contains_run_from_turn"]:
                if key in exp and not 1 <= exp[key] < number:
                    raise ValueError(f"{cid}: invalid previous turn reference")
            if "replay_turn" in turn and not 1 <= turn["replay_turn"] < number:
                raise ValueError(f"{cid}: invalid replay reference")
            current_aliases = set()
            for instance in exp.get("instances", []):
                if set(instance) - INSTANCE:
                    raise ValueError(f"{cid}: unsupported instance assertion")
                codes = instance.get("codes", [instance.get("code")])
                if not codes or any(c not in ROUTES for c in codes):
                    raise ValueError(f"{cid}: invalid intent")
                if instance["alias"] in current_aliases or (instance.get("resume") and instance["alias"] not in aliases):
                    raise ValueError(f"{cid}: invalid alias lifecycle")
                current_aliases.add(instance["alias"])
                if instance["state"] not in {"COMPLETED", "FAILED", "NEED_CLARIFICATION", "READY"}:
                    raise ValueError(f"{cid}: invalid state")
                for ref in instance.get("fact_refs", []):
                    get_path(fixture, ref)
            aliases |= current_aliases
        signature = tuple((t.get("op", "message"), t.get("content", ""), t.get("actor_ref", case["actor_ref"])) for t in rendered["turns"])
        # Cases with different fixtures/HTTP adversarial headers may deliberately share words.
        signature += (str(case.get("requires", [])), str([t.get("headers", {}) for t in rendered["turns"]]), str([t.get("client_message_id") for t in rendered["turns"]]))
        if signature in questions:
            raise ValueError(f"duplicate conversation: {cid}")
        questions.add(signature)
    return {"cases": len(cases), "families": len(families), "turns": sum(len(c["turns"]) for c in cases)}
