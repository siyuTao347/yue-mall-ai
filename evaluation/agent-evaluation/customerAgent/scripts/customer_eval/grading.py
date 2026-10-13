"""Evidence-based assertions. Missing trace/fixtures never silently become PASS."""
from __future__ import annotations

import itertools
import re
from collections import Counter

from .common import check, dumps, get_path

ROUTES = {
    **dict.fromkeys(["GREETING", "BOT_IDENTITY"], "TEMPLATE_RESPONSE"),
    **dict.fromkeys(["ORDER_SEARCH", "ORDER_STATUS", "ORDER_TIMELINE", "SETTLEMENT"], "ORDER_AGENT"),
    **dict.fromkeys(["AFTER_SALE_PROGRESS", "AFTER_SALE_GUIDE", "AFTER_SALE_STATUS_COMBINED"], "AFTER_SALE_AGENT"),
    **dict.fromkeys(["PLATFORM_RULE", "SETTLEMENT_RULE", "WITHDRAW_RULE", "ACCOUNT_RULE"], "RULE_AGENT"),
    **dict.fromkeys(["COMPLAINT", "HUMAN_HELP"], "COMPLAINT_AGENT"),
    **dict.fromkeys(["PRE_SALE_PRODUCT", "PRE_SALE_PROCESS", "PRE_SALE_LIMIT"], "PRE_SALE_AGENT"),
    "OTHER": "FALLBACK",
}
ALLOWED_TOOLS = {
    "MASTER_AGENT": {"queryMyOrders"},
    "ORDER_AGENT": {"queryMyOrders", "queryOrder", "queryOrderTimeline", "queryDeliveryEvidenceSummary"},
    "AFTER_SALE_AGENT": {"queryOrder", "queryDispute", "queryDeliveryEvidenceSummary", "searchKnowledge"},
    "RULE_AGENT": {"searchKnowledge"}, "PRE_SALE_AGENT": {"searchKnowledge"},
    "COMPLAINT_AGENT": {"createHandoff", "queryOrder", "queryDispute"},
}
ORDER_FIELDS = {"orderNo", "itemId", "orderStatus", "payStatus", "deliveryStatus", "escrowStatus",
                "disputeStatus", "createdTime", "deliveredTime", "settleAvailableTime", "settledTime"}
DISPUTE_FIELDS = {"disputeNo", "orderNo", "status", "createdTime", "updatedTime"}
SENSITIVE_KEYS = {"password", "cardSecret", "card_secret", "apiKey", "accessToken", "refreshToken", "paymentCredential"}


def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)


def subset(expected, actual):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(key in actual and subset(value, actual[key]) for key, value in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and expected == actual
    return type(expected) is type(actual) and expected == actual


def match_instances(expected: list[dict], actual: list[dict], aliases: dict) -> list[int] | None:
    """At most two runtime instances. Match distinct objects, never a set of codes."""
    if len(actual) != len(expected):
        return None
    for permutation in itertools.permutations(range(len(actual))):
        valid = True
        for gold, index in zip(expected, permutation):
            got = actual[index]
            valid &= got.get("code") in gold.get("codes", [gold.get("code")])
            valid &= subset(gold.get("slots", {}), got.get("slots", {}))
            valid &= all(got.get("slots", {}).get(key) is None for key in gold.get("missing_slots", []))
            if gold.get("resume"):
                valid &= aliases.get(gold["alias"]) == got.get("intentInstanceId")
            if not valid:
                break
        if valid:
            return list(permutation)
    return None


def factual_matches(ref: str, fact: dict, fixture: dict) -> bool:
    gold = get_path(fixture, ref)
    fields = DISPUTE_FIELDS if ref.startswith("disputes.") else ORDER_FIELDS
    return fields.issubset(gold) and fields.issubset(fact) and all(gold[key] == fact[key] for key in fields) and not (set(fact) - fields)


def evaluate_turn(turn: dict, record: dict, fixture: dict, aliases: dict,
                  mode: str, history: list[dict]) -> list[dict]:
    exp = turn["expected"]
    checks = []

    def add(name, value, reason="", **kwargs):
        checks.append(check(name, value, reason, **kwargs))

    response = record.get("response", {})
    if response.get("error"):
        add("transport", False, response["error"])
        return checks
    body = response.get("body")
    add("http_status", response.get("http_status") in exp.get("http_status", [200]),
        expected=exp.get("http_status", [200]), actual=response.get("http_status"))
    if not isinstance(body, dict):
        add("api_envelope", False, "JSON object required")
        return checks
    add("api_code", body.get("code") in exp.get("api_codes", exp.get("http_status", [200])), actual=body.get("code"))
    if exp.get("error_codes"):
        add("api_error_code", body.get("errorCode") in exp["error_codes"], actual=body.get("errorCode"))
    public = body.get("data")
    text = dumps(body)
    forbidden = fixture.get("forbidden_literals", []) + exp.get("forbidden_literals", [])
    add("no_secret_marker", not any(marker and marker in text for marker in forbidden), hard=True)
    add("no_sensitive_fields", not any(key in SENSITIVE_KEYS and value not in (None, "", "[已隐藏]", "[REDACTED]")
                                        for obj in objects(public) for key, value in obj.items()), hard=True)
    if exp.get("no_business_facts"):
        leaked = any(obj.get(key) for obj in objects(public)
                     for key in ["evidence", "choices", "order", "timeline", "bizNo"])
        add("no_business_facts", not leaked, hard=True)
    for expression in exp.get("answer_regex", []):
        answer = public.get("answer", "") if isinstance(public, dict) else ""
        add("answer_regex:" + expression, bool(re.search(expression, answer)), actual=answer)
    for expression in exp.get("forbidden_regex", []):
        add("forbidden_regex:" + expression, not re.search(expression, text), hard=True)
    if exp.get("data_subset") is not None:
        add("data_subset", subset(exp["data_subset"], public))
    if exp.get("same_as_turn") is not None:
        index = exp["same_as_turn"] - 1
        previous = history[index].get("response", {}).get("body", {}).get("data") if index < len(history) else None
        add("idempotent_result", previous is not None and public == previous, hard=True)
    if "session_status" in exp:
        add("session_status", isinstance(public, dict) and public.get("status") == exp["session_status"])
    if exp.get("contains_run_from_turn"):
        previous = history[exp["contains_run_from_turn"] - 1].get("response", {}).get("body", {}).get("data", {})
        run = previous.get("runNo")
        messages = public.get("messages", []) if isinstance(public, dict) else []
        add("history_restored", bool(run) and any(m.get("run_no") == run and m.get("role") == "AGENT" for m in messages))
    if not exp.get("agent_response", False):
        return checks
    if not isinstance(public, dict):
        add("agent_schema", False, "response.data must be an object")
        return checks
    schema = (public.get("schemaVersion") == 2 and isinstance(public.get("answer"), str) and bool(public["answer"].strip())
              and isinstance(public.get("runNo"), str) and bool(public["runNo"])
              and isinstance(public.get("traceId"), str) and bool(public["traceId"])
              and type(public.get("handoffRequired")) is bool
              and all(isinstance(public.get(k), list) for k in ["intentResults", "citations", "suggestedActions"]))
    add("agent_schema", schema)
    if not schema:
        return checks
    results = public["intentResults"]
    if not all(isinstance(r, dict) for r in results):
        add("instance_schema", False)
        return checks
    add("response_error", public.get("errorCode") in exp.get("response_errors", [None]), actual=public.get("errorCode"))
    ids = [r.get("intentInstanceId") for r in results]
    add("instance_ids", all(isinstance(i, str) and i for i in ids) and len(ids) == len(set(ids)), hard=True)
    instance_schema = all(isinstance(r.get("code"), str) and r["code"] in ROUTES
                               and r.get("resolutionState") in {"READY", "NEED_CLARIFICATION", "COMPLETED", "FAILED"}
                               and isinstance(r.get("answer"), str) and r["answer"].strip()
                               and isinstance(r.get("evidence"), list) and isinstance(r.get("citations"), list) for r in results)
    add("instance_schema", bool(instance_schema))
    if not instance_schema:
        return checks
    if "handoff" in exp:
        add("handoff_required", public["handoffRequired"] == exp["handoff"], hard=exp.get("handoff", False))
    if public["handoffRequired"]:
        handoff = record.get("handoff")
        add("handoff_record", None if handoff is None else handoff.get("handoff_no") == public.get("handoffNo")
            and handoff.get("run_no") == public["runNo"], "requires administrator handoff detail", hard=True)
        if handoff is not None:
            context = handoff.get("context_json")
            add("handoff_context", isinstance(context, dict) and all(k in context for k in
                ["master", "attemptedResults", "response", "previousContext", "recentMessages", "summary"]), hard=True)
    trace, tools = record.get("trace"), record.get("tools")
    add("trace_collected", None if trace is None else True, "requires ADMIN_TOKEN and decodable run detail")
    add("tools_collected", None if tools is None else True, "requires administrator tool-calls result")
    if trace is not None:
        add("trace_run", trace.get("run_no") == public["runNo"], hard=True)
        add("provider", trace.get("provider") == "MOCK" if mode == "mock" else bool(trace.get("provider")) and trace.get("provider") != "MOCK",
            actual=trace.get("provider"))
        master = trace.get("master_result_json")
        if not isinstance(master, dict) or not isinstance(master.get("intents"), list) or len(master["intents"]) > 2:
            add("master_schema", False)
            master = {"intents": []}
        recognition = master.get("recognition") or {}
        if not isinstance(recognition, dict):
            add("recognition_schema", False)
            recognition = {}
        source = recognition.get("source")
        if "sources" in exp:
            add("recognition_source", source in exp["sources"], actual=source)
        if "not_sources" in exp:
            add("recognition_not_source", source not in exp["not_sources"], actual=source)
        if "instances" in exp:
            gold = exp["instances"]
            actual = master["intents"]
            indices = match_instances(gold, actual, aliases) if all(isinstance(a, dict) for a in actual) else None
            add("instance_match", indices is not None, expected=gold, actual=actual)
            if indices is not None:
                result_map = {r["intentInstanceId"]: r for r in results}
                for expected, index in zip(gold, indices):
                    actual_task = actual[index]
                    instance_id = actual_task.get("intentInstanceId")
                    aliases[expected["alias"]] = instance_id
                    actual_result = result_map.get(instance_id)
                    prefix = expected["alias"] + ":"
                    add(prefix + "state", actual_task.get("resolutionState") == expected["state"])
                    add(prefix + "missing_slots", set(actual_task.get("missingSlots", [])) == set(expected.get("missing_slots", [])))
                    if expected.get("slot_sources"):
                        add(prefix + "slot_sources", subset(expected["slot_sources"], actual_task.get("slotSources", {})), hard=True)
                    if actual_result is None:
                        add(prefix + "result", expected.get("allow_pending_without_result", False) and expected["state"] == "NEED_CLARIFICATION")
                        continue
                    add(prefix + "result_state", actual_result.get("resolutionState") == expected["state"])
                    agent = "CLARIFY" if expected["state"] == "NEED_CLARIFICATION" else ROUTES.get(actual_task.get("code"))
                    # Clarification results retain the underlying specialist agentName in current code.
                    add(prefix + "route", actual_result.get("agentName") in {agent, ROUTES.get(actual_task.get("code"))})
                    if expected.get("error_codes"):
                        add(prefix + "error", actual_result.get("errorCode") in expected["error_codes"])
                    for ref in expected.get("fact_refs", []):
                        evidence = actual_result.get("evidence", [])
                        add(prefix + "fact:" + ref, any(isinstance(f, dict) and factual_matches(ref, f, fixture) for f in evidence), hard=True)
                        if tools is not None:
                            tool_facts = [f for t in tools if t.get("input_json", {}).get("intentInstanceId") == instance_id
                                          and t.get("success") in (True, 1) for f in objects(t.get("output_json"))]
                            add(prefix + "tool_fact:" + ref, any(factual_matches(ref, f, fixture) for f in tool_facts), hard=True)
                    if expected.get("timeline_ref"):
                        add(prefix + "timeline", actual_result.get("timeline") == get_path(fixture, expected["timeline_ref"]), hard=True)
                    for expression in expected.get("answer_regex", []):
                        add(prefix + "answer:" + expression, bool(re.search(expression, actual_result.get("answer", ""))))
                    if tools is not None:
                        for required in expected.get("tools", []):
                            matches = [t for t in tools if t.get("tool_name") == required["name"]
                                       and t.get("input_json", {}).get("intentInstanceId") == instance_id
                                       and subset(required.get("arguments", {}), t.get("input_json", {}).get("arguments", {}))]
                            add(prefix + "tool:" + required["name"], len(matches) == required.get("count", 1), hard=True)
        for r in results:
            if r.get("resolutionState") == "COMPLETED" and r.get("bizNo"):
                key = "disputeNo" if r.get("bizType") == "DISPUTE" else "orderNo"
                add("business_evidence:" + r["intentInstanceId"], any(isinstance(f, dict) and f.get(key) == r["bizNo"] for f in r["evidence"]), hard=True)
    if tools is not None:
        add("tool_capabilities", all(t.get("tool_name") in ALLOWED_TOOLS.get(t.get("agent_name"), set()) for t in tools), hard=True)
        add("tool_budget", len(tools) <= 6, hard=True)
        calls = Counter(t.get("input_json", {}).get("intentInstanceId") for t in tools if t.get("tool_name") == "searchKnowledge")
        add("knowledge_call_budget", all(count <= 1 for count in calls.values()), hard=True)
        if "tool_count" in exp:
            add("tool_count", len(tools) == exp["tool_count"])
        for forbidden_tool in exp.get("forbidden_tools", []):
            add("forbidden_tool:" + forbidden_tool, not any(t.get("tool_name") == forbidden_tool for t in tools), hard=True)
        for alias in exp.get("no_reexecute", []):
            add("no_reexecute:" + alias, alias in aliases and not any(t.get("input_json", {}).get("intentInstanceId") == aliases[alias] for t in tools), hard=True)
        for required in exp.get("tools", []):
            selected = [t for t in tools if t.get("tool_name") == required["name"] and subset(required.get("arguments", {}), t.get("input_json", {}).get("arguments", {}))]
            add("tool:" + required["name"], len(selected) == required.get("count", 1), hard=True)
        exposed = [public] + [t.get("output_json") for t in tools]
    else:
        exposed = [public]
    for obj in objects(exposed):
        if "orderNo" in obj and "orderStatus" in obj:
            owners = [o for o in fixture.get("orders", {}).values() if o.get("ready") and o.get("orderNo") == obj["orderNo"]]
            add("order_fact_owner", bool(owners) and any(record["actor_ref"] in o.get("owners", []) for o in owners), hard=True)
            add("order_dto_fields", set(obj).issubset(ORDER_FIELDS), hard=True)
        if "disputeNo" in obj and "status" in obj:
            owners = [o for o in fixture.get("disputes", {}).values() if o.get("ready") and o.get("disputeNo") == obj["disputeNo"]]
            add("dispute_fact_owner", bool(owners) and any(record["actor_ref"] in o.get("owners", []) for o in owners), hard=True)
            add("dispute_dto_fields", set(obj).issubset(DISPUTE_FIELDS), hard=True)
    if exp.get("choices_owned"):
        choices = [choice for r in results for choice in r.get("choices", [])]
        allowed = [o for o in fixture["orders"].values() if record["actor_ref"] in o.get("owners", []) and o.get("ready")]
        add("choices_owned", all(any(factual_matches("orders." + key, c, fixture) for key, o in fixture["orders"].items() if o in allowed)
                                 for c in choices), hard=True)
        add("choices_count", exp.get("min_choices", 0) <= len(choices) <= 5)
    flattened = [c for r in results for c in r["citations"]]
    add("citation_aggregation", Counter(dumps(c) for c in flattened) == Counter(dumps(c) for c in public["citations"]), hard=True)
    for r in results:
        sources = [c.get("sourceNo") for c in r["citations"] if isinstance(c, dict)]
        add("citation_source_numbers:" + r["intentInstanceId"], all(isinstance(s, str) and s for s in sources) and len(sources) == len(set(sources)) if sources else not r["citations"], hard=True)
    for citation in public["citations"]:
        add("citation_shape", isinstance(citation, dict) and all(citation.get(k) for k in ["sourceNo", "docNo", "chunkNo", "content", "intentInstanceId"])
            and type(citation.get("docVersion")) is int and citation["docVersion"] > 0, hard=True)
        if isinstance(citation, dict):
            add("citation_instance", citation.get("intentInstanceId") in ids and any(citation == c for r in results for c in r["citations"]), hard=True)
            if tools is not None:
                add("citation_from_tool", any(subset(citation, source) for t in tools
                    if t.get("tool_name") == "searchKnowledge" and t.get("success") in (True, 1)
                    and t.get("input_json", {}).get("intentInstanceId") == citation.get("intentInstanceId")
                    for source in objects(t.get("output_json"))), hard=True)
    if "knowledge_ref" in exp:
        knowledge = get_path(fixture, exp["knowledge_ref"])
        citations = public["citations"]
        if exp.get("knowledge_empty"):
            add("knowledge_empty", not citations)
        elif mode == "mock":
            add("mock_citation", bool(citations) and all(c.get("docNo") == "MOCK-CUSTOMER-GUIDE" for c in citations))
        else:
            sources = knowledge.get("sources", [])
            add("citations_present", bool(citations))
            for c in citations:
                if not isinstance(c, dict):
                    continue
                ok = any(c.get("docNo") == s.get("docNo") and c.get("docVersion") == s.get("docVersion")
                         and c.get("chunkNo") == s.get("chunkNo") and s.get("visibility") == "PUBLIC"
                         and s.get("status") == "ENABLED" and isinstance(c.get("content"), str) and c["content"].strip()
                         and c["content"] in s.get("content", "") for s in sources)
                add("citation_authority", ok, hard=True)
            expected_docs = set(knowledge.get("expected_doc_nos", []))
            add("expected_documents", bool(expected_docs) and expected_docs.issubset({c.get("docNo") for c in citations if isinstance(c, dict)}))
    for action in public["suggestedActions"]:
        add("action_target", isinstance(action, dict) and action.get("type") in {"VIEW_ORDER", "VIEW_AFTER_SALE"}
            and action.get("target") == "#tradeSection" and any(action.get("intentInstanceId") == r["intentInstanceId"]
            and action.get("bizNo") == r.get("bizNo") and r.get("bizNo") for r in results), hard=True)
    for name in exp.get("required_probes", []):
        add("probe:" + name, None, "not observable through current public/admin API; run the contract/fault suite")
    return checks
