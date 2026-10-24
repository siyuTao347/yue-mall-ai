#!/usr/bin/env python3
r"""仅评估 CustomerMaster 三层意图识别；拿到意图后结束。Python 3.9+ 标准库。

用法（在仓库根目录）：
  python3 evaluation/intention-evaluation/evaluate_intents.py --validate-only
  python3 evaluation/intention-evaluation/evaluate_intents.py --self-test

  # 只需 INTENT_EVAL_ADMIN_TOKEN 环境变量（管理员 JWT，可带 Bearer 前缀）。
  python3 evaluation/intention-evaluation/evaluate_intents.py --limit 10
  python3 evaluation/intention-evaluation/evaluate_intents.py \
    --base-url http://localhost:8080

先重启包含 CustomerIntentEvaluationController 的 mall-agent-service。
接口：GET /api/admin/agent/intent-evaluation 检查能力；
      POST /api/admin/agent/intent-evaluation/recognize 仅识别意图。
脚本不会调用 sessions/messages、订单查询、转人工、记忆读写或回答生成接口。
若新接口未部署则明确报错，不退回完整客服消息接口。

JSON 自带合成编号，不需要真实业务数据。可用 --fixture ORDER_A=SO202610200001
或 INTENT_EVAL_FIXTURES='{"ORDER_A":"..."}' 覆盖，覆盖值仍仅作为识别输入。
30 条上下文用例直接传入预设 context/history；不执行前置对话或模拟成功的业务调用。
因此本次成绩衡量给定上下文下的识别能力，不衡量实际上下文建立或业务执行效果。
筛选示例：--difficulty hard / --tag negation / --case-id INT-101 --case-id INT-149。

脚本不调用 DeepSeek 或任何模型服务，不使用 LLM 判分。后端仍须按正常配置运行：
L2 需要已发布的意图库及向量检索，L3 需要后端模型配置；MOCK 结果不能当真实模型成绩。
这里测的是实际级联路由，各层准确率是该层被选中后的条件准确率，不是独立层召回率。

MEMORY_FORGET / HUMAN_HELP 仅识别标签，默认全部参与评估。
JWT 只从环境读取，不落入代码或报告；后端识别本身仍可能产生模型费用。

输出目录默认 outputs/<UTC时间-随机ID>/：
  results.jsonl：逐条结果、分类快照及运行元数据，便于复核（不含 JWT）。
  summary.json：匹配率、覆盖率、各难度/层级/意图指标及运行有效性提示。
  failures.csv：错误、漏槽、层级不符、跳过及未执行的用例。
完整匹配采用意图多重集合比较，保留重复意图；槽位按实例一一匹配。
接口异常独立统计，并保留在选定总数中，避免抬高覆盖率。
HTTP 错误保留 http_status、error_code、api_message、trace_id，便于关联后端日志；
只保存这些字段，不保存完整错误响应或凭证。
HTTP POST 不自动重试，避免重复识别产生额外模型费用。
退出码：0=评估完成（不代表全对），1=有执行错误/跳过/MOCK/未完成，2=配置错误。
"""

import argparse
import collections
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parent
ENDPOINT = "/api/admin/agent/intent-evaluation"
INTENTS = frozenset("""
GREETING BOT_IDENTITY OTHER ORDER_SEARCH ORDER_STATUS ORDER_TIMELINE SETTLEMENT
AFTER_SALE_PROGRESS AFTER_SALE_GUIDE AFTER_SALE_STATUS_COMBINED PLATFORM_RULE
SETTLEMENT_RULE WITHDRAW_RULE ACCOUNT_RULE COMPLAINT HUMAN_HELP PRE_SALE_PRODUCT
PRE_SALE_PROCESS PRE_SALE_LIMIT MEMORY_QUERY MEMORY_FORGET
""".split())
SOURCES = frozenset(("L1_RULE", "L2_RAG", "L3_LLM", "ESCALATION_RULE", "CONTEXT_RESUME"))
PLACEHOLDER = re.compile(r"\{\{([A-Z][A-Z0-9_]*)\}\}")
DEGRADED = frozenset((
    "INTENT_RAG_DISABLED", "MOCK_CLASSIFICATION", "INTENT_RELEASE_UNPUBLISHED",
    "INTENT_RELEASE_INCOMPLETE", "INTENT_RAG_UNAVAILABLE",
))


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def object_json(value, field):
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{field} 不是有效 JSON") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{field} 不是对象")
    return value


def validate_dataset(data):
    if data.get("schema_version") != 2:
        raise ValueError("只支持仅识别模式的数据集 schema_version=2")
    cases = data.get("cases")
    if not isinstance(cases, list) or not cases or data.get("case_count") != len(cases):
        raise ValueError("case_count 与非空 cases 列表不一致")
    ids, texts = set(), set()
    for case in cases:
        cid = case.get("id")
        if not isinstance(cid, str) or not cid or cid in ids:
            raise ValueError(f"用例 ID 为空或重复：{cid}")
        ids.add(cid)
        if case.get("difficulty") not in ("easy", "medium", "hard"):
            raise ValueError(f"{cid}: difficulty 不合法")
        if not isinstance(case.get("question"), str) or not case["question"].strip():
            raise ValueError(f"{cid}: question 不能为空")
        if not isinstance(case.get("tags"), list) or not all(
                isinstance(tag, str) and tag for tag in case["tags"]):
            raise ValueError(f"{cid}: tags 必须为字符串数组")
        if "setup" in case:
            raise ValueError(f"{cid}: 仅识别模式不执行 setup，请改为显式 context/history")
        if not isinstance(case.get("context", {}), dict):
            raise ValueError(f"{cid}: context 必须为对象")
        history = case.get("history", [])
        if not isinstance(history, list) or len(history) > 8 or any(
                not isinstance(m, dict) or m.get("role") not in ("USER", "ASSISTANT")
                or not isinstance(m.get("content"), str) or not m["content"].strip()
                for m in history):
            raise ValueError(f"{cid}: history 必须为不超过 8 条的 USER/ASSISTANT 消息")
        signature = json.dumps([case.get("context", {}), history, case["question"]],
                               ensure_ascii=False, sort_keys=True)
        if signature in texts:
            raise ValueError(f"{cid}: 问题与前置对话完全重复")
        texts.add(signature)
        for step in [case]:
            codes = step.get("expected_intents")
            if not isinstance(codes, list) or not codes or any(c not in INTENTS for c in codes):
                raise ValueError(f"{cid}: expected_intents 含非法标签")
            sources = step.get("expected_sources", [])
            if not isinstance(sources, list) or any(s not in SOURCES for s in sources):
                raise ValueError(f"{cid}: expected_sources 不合法")
            labels = step.get("expected_slots", [])
            if not isinstance(labels, list):
                raise ValueError(f"{cid}: expected_slots 必须为数组")
            if collections.Counter(x["code"] for x in labels) - collections.Counter(codes):
                raise ValueError(f"{cid}: 槽位实例多于对应的意图实例")
            for label in labels:
                slots = label.get("slots")
                if not isinstance(slots, dict) or set(slots) - {"orderNo", "disputeNo", "itemId"}:
                    raise ValueError(f"{cid}: 槽位名称不合法")
                if any(not isinstance(v, str) or not v for v in slots.values()):
                    raise ValueError(f"{cid}: 槽位值必须为非空字符串")
        placeholders = set(PLACEHOLDER.findall(json.dumps(case, ensure_ascii=False)))
        if placeholders - set(data.get("fixtures", {})):
            raise ValueError(f"{cid}: fixture 未声明")
    return cases


def substitute(value, fixtures):
    if isinstance(value, str):
        return PLACEHOLDER.sub(lambda m: fixtures[m.group(1)], value)
    if isinstance(value, list):
        return [substitute(v, fixtures) for v in value]
    if isinstance(value, dict):
        return {k: substitute(v, fixtures) for k, v in value.items()}
    return value


def slots_match(labels, actual):
    """小规模二分匹配；不能让两个金标实例复用同一个预测实例。"""
    def visit(index, used):
        if index == len(labels):
            return True
        expected = labels[index]
        for i, candidate in enumerate(actual):
            slots = candidate.get("slots", {})
            if (i not in used and candidate["code"] == expected["code"]
                    and all(str(slots.get(k)) == str(v) for k, v in expected["slots"].items())
                    and visit(index + 1, used | {i})):
                return True
        return False
    return visit(0, set())


def require_recognition_only(value):
    if (not isinstance(value, dict) or value.get("contractVersion") != 1
            or value.get("mode") != "RECOGNITION_ONLY"
            or value.get("businessExecution") is not False
            or value.get("contextMode") != "EXPLICIT_FIXTURE"
            or not isinstance(value.get("mock"), bool)):
        raise ValueError("后端未确认 RECOGNITION_ONLY 合约，停止评估；不会回退完整客服流程")
    return value


def observation(evidence):
    require_recognition_only(evidence)
    master = object_json(evidence.get("master"), "master")
    intents = master.get("intents")
    if not isinstance(intents, list) or not intents:
        raise ValueError("master.intents 为空或不是数组")
    for intent in intents:
        if not isinstance(intent, dict) or intent.get("code") not in INTENTS:
            raise ValueError("后端返回未知意图编码")
        if not isinstance(intent.get("slots", {}), dict):
            raise ValueError("意图 slots 不是对象")
    recognition = master.get("recognition")
    if not isinstance(recognition, dict) or recognition.get("source") not in SOURCES:
        raise ValueError("缺少有效 recognition.source，无法确认级联来源")
    return {
        "classification_id": evidence.get("classificationId"),
        "intents": [{k: i[k] for k in (
            "intentInstanceId", "code", "slots", "resolutionState", "missingSlots",
            "slotSources", "confidence", "similarity",
        ) if k in i} for i in intents],
        "recognition": recognition,
        "implementation_sha256": evidence.get("implementationSha256"),
        "backend": {k: evidence.get(k) for k in ("provider", "masterModel", "mock", "ragEnabled")},
        "elapsed_ms": evidence.get("elapsedMs"),
        "input_tokens": evidence.get("inputTokens"),
        "output_tokens": evidence.get("outputTokens"),
    }


def grade(case, observed):
    actual = observed["intents"]
    exact = collections.Counter(i["code"] for i in actual) == collections.Counter(case["expected_intents"])
    slot_result = slots_match(case["expected_slots"], actual) if case.get("expected_slots") else None
    source_result = (observed["recognition"]["source"] in case["expected_sources"]
                     if case.get("expected_sources") else None)
    return {"intent_match": exact, "slot_match": slot_result, "source_match": source_result}


class ApiError(RuntimeError):
    def __init__(self, message, **details):
        super().__init__(message)
        self.details = {key: value for key, value in details.items() if value is not None}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # 不把用户/管理员 JWT 转发到重定向地址。
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base, admin_token, timeout):
        self.base = base.rstrip("/")
        self.admin_token = re.sub(r"(?i)^bearer\s+", "", admin_token.strip())
        self.timeout = timeout
        self.opener = urllib.request.build_opener(NoRedirect())

    def error_text(self, value):
        if not isinstance(value, str):
            return None
        # Some proxies echo request headers. Never persist an echoed credential.
        if self.admin_token:
            value = value.replace(self.admin_token, "[REDACTED]")
        value = re.sub(r"(?i)\bbearer\s+[A-Za-z0-9._-]+", "Bearer [REDACTED]", value)
        value = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[REDACTED]", value)
        value = re.sub(r"(?i)(password|api[_-]?key|token|密码)\s*[:=：]\s*[^\s，。]+",
                       r"\1=[REDACTED]", value)
        return re.sub(r"\s+", " ", value).strip()[:1000]

    def response_error(self, method, path, http_status, wrapper):
        wrapper = wrapper if isinstance(wrapper, dict) else {}
        code = self.error_text(wrapper.get("errorCode"))
        message = self.error_text(wrapper.get("msg"))
        trace = self.error_text(wrapper.get("traceId"))
        reason = f"{method} {path}: HTTP {http_status}"
        if code:
            reason += f" [{code}]"
        if message:
            reason += " " + message
        if trace:
            reason += f" (traceId={trace})"
        if http_status == 404:
            reason += "；请确认只识别接口已部署，不会回退完整客服流程"
        return ApiError(reason, http_status=http_status, error_code=code,
                        api_message=message, trace_id=trace)

    def call(self, method, path, body=None):
        if (method, path) not in (("GET", ENDPOINT), ("POST", ENDPOINT + "/recognize")):
            raise ValueError("仅识别评估客户端不允许调用其他接口")
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        headers = {"Authorization": "Bearer " + self.admin_token,
                   "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=payload, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                http_status = response.status
                wrapper = json.loads(response.read())
        except urllib.error.HTTPError as exc:
            try:
                wrapper = json.loads(exc.read(16384))
            except (ValueError, OSError):
                wrapper = None
            finally:
                exc.close()
            raise self.response_error(method, path, exc.code, wrapper) from None
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            # 不输出可能包含代理凭证/敏感响应的异常内容。
            raise ApiError(f"{method} {path}: 网络失败或超时 ({type(exc).__name__})") from None
        except (json.JSONDecodeError, UnicodeError):
            raise ApiError(f"{method} {path}: 响应不是 JSON") from None
        if not isinstance(wrapper, dict) or wrapper.get("code") != 200 or "data" not in wrapper:
            raise self.response_error(method, path, http_status, wrapper)
        return wrapper["data"]

    def recognize(self, content, context, history):
        # 金标、rationale、expected_slots 等绝不发送给被测服务。
        return observation(self.call("POST", ENDPOINT + "/recognize",
                                     {"content": content, "context": context, "history": history}))


def evaluate_case(case, client, fixtures):
    record = {"id": case["id"], "difficulty": case["difficulty"], "tags": case["tags"],
              "question": case["question"], "expected_intents": case["expected_intents"],
              "expected_slots": case.get("expected_slots", []),
              "expected_sources": case.get("expected_sources", []),
              "context_mode": "context_fixture" if case.get("context") or case.get("history") else "single_turn"}
    missing = set(PLACEHOLDER.findall(json.dumps(case, ensure_ascii=False))) - fixtures.keys()
    if missing:
        record.update(status="SKIPPED", reason="缺少合成 fixture: " + ", ".join(sorted(missing)))
        return record
    case = substitute(case, fixtures)
    record.update(question=case["question"], expected_slots=case.get("expected_slots", []),
                  context=case.get("context", {}), history=case.get("history", []))
    started = time.monotonic()
    try:
        record["observed"] = client.recognize(case["question"], record["context"], record["history"])
        record.update(grade(case, record["observed"]))
        record["status"] = "EVALUATED"
    except (ApiError, ValueError) as exc:
        record.update(status="ERROR", reason=str(exc))
        if isinstance(exc, ApiError):
            record["error"] = exc.details
    except KeyboardInterrupt:
        record.update(status="INTERRUPTED", reason="用户中断；已发送的识别请求可能仍在运行")
    finally:
        record["elapsed_ms"] = round((time.monotonic() - started) * 1000)
    return record


def ratio(numerator, denominator):
    return round(numerator / denominator, 6) if denominator else None


def group_metrics(records):
    tested = [r for r in records if r["status"] == "EVALUATED"]
    correct = sum(r["intent_match"] for r in tested)
    return {"selected": len(records), "evaluated": len(tested), "exact_matches": correct,
            "accuracy_on_evaluated": ratio(correct, len(tested)),
            "coverage": ratio(len(tested), len(records)),
            "matches_over_selected": ratio(correct, len(records))}


def summarize(records, meta):
    tested = [r for r in records if r["status"] == "EVALUATED"]
    summary = {"metadata": meta, "finished_at": utc_now(), **group_metrics(records),
               "status_counts": dict(collections.Counter(r["status"] for r in records))}
    summary["error_groups"] = dict(collections.Counter(
        f"{r.get('error', {}).get('http_status', 'N/A')} "
        f"{r.get('error', {}).get('error_code', 'UNKNOWN')}: "
        f"{r.get('error', {}).get('api_message', r.get('reason', ''))}"
        for r in records if r["status"] == "ERROR"))
    summary["by_difficulty"] = {
        d: group_metrics([r for r in records if r["difficulty"] == d]) for d in ("easy", "medium", "hard")}
    summary["by_source"] = {
        source: group_metrics([r for r in tested if r["observed"]["recognition"]["source"] == source])
        for source in sorted(SOURCES)}
    summary["by_tag"] = {tag: group_metrics([r for r in records if tag in r["tags"]])
                         for tag in sorted({tag for r in records for tag in r["tags"]})}
    summary["by_context_mode"] = {
        mode: group_metrics([r for r in records if r.get("context_mode") == mode])
        for mode in ("single_turn", "context_fixture")}
    support, predicted, tp = collections.Counter(), collections.Counter(), collections.Counter()
    for r in tested:
        gold = collections.Counter(r["expected_intents"])
        actual = collections.Counter(i["code"] for i in r["observed"]["intents"])
        support.update(gold)
        predicted.update(actual)
        tp.update(gold & actual)
    per_intent = {}
    for code in sorted(support.keys() | predicted.keys()):
        hit, true, pred = tp[code], support[code], predicted[code]
        per_intent[code] = {"support": true, "predicted": pred, "tp": hit,
                            "fp": pred - hit, "fn": true - hit,
                            "precision": ratio(hit, pred), "recall": ratio(hit, true),
                            "f1": ratio(2 * hit, pred + true)}
    summary["per_intent_on_evaluated"] = per_intent
    summary["micro_precision"] = ratio(sum(tp.values()), sum(predicted.values()))
    summary["micro_recall"] = ratio(sum(tp.values()), sum(support.values()))
    summary["micro_f1"] = ratio(2 * sum(tp.values()), sum(predicted.values()) + sum(support.values()))
    summary["macro_f1"] = (round(sum(v["f1"] for v in per_intent.values()) / len(per_intent), 6)
                            if per_intent else None)
    for field in ("slot_match", "source_match"):
        eligible = [r for r in tested if r.get(field) is not None]
        summary[field] = {"scored": len(eligible), "matched": sum(r[field] for r in eligible),
                          "accuracy": ratio(sum(r[field] for r in eligible), len(eligible))}
    observations = [r["observed"] for r in tested]
    fallbacks = collections.Counter(o["recognition"].get("fallbackReason") for o in observations
                                    if o["recognition"].get("fallbackReason"))
    summary["fallback_reasons"] = dict(fallbacks)
    releases = {json.dumps({k: o["recognition"].get(k) for k in
                            ("releaseNo", "manifestHash", "embeddingModel")}, sort_keys=True)
                for o in observations if o["recognition"].get("releaseNo")}
    configs = {json.dumps(o["backend"], sort_keys=True) for o in observations}
    hashes = {o.get("implementation_sha256") for o in observations if o.get("implementation_sha256")}
    summary["recognition_releases"] = [json.loads(r) for r in sorted(releases)]
    summary["backend_configs"] = [json.loads(c) for c in sorted(configs)]
    summary["implementation_sha256"] = sorted(hashes)
    summary["mock_detected"] = any(o["backend"].get("mock") for o in observations)
    warnings = []
    if summary["coverage"] != 1:
        warnings.append("存在跳过、异常或未执行；有效样本准确率不能代表完整选定集。")
    if summary["mock_detected"] or fallbacks["MOCK_CLASSIFICATION"]:
        warnings.append("后端使用 MOCK；不得作为真实模型的三层识别成绩。")
    if DEGRADED & fallbacks.keys():
        warnings.append("存在 L2 未发布、不可用、关闭或 MOCK 降级，详见 fallback_reasons。")
    missing_sources = {"L1_RULE", "L2_RAG", "L3_LLM"} - {
        o["recognition"]["source"] for o in observations}
    if missing_sources:
        warnings.append("未观察到所有三层，缺少：" + ", ".join(sorted(missing_sources)))
    if len(releases) > 1 or len(hashes) > 1 or len(configs) > 1:
        warnings.append("运行期间意图库、后端配置或实现指纹变化，不宜视为同一版本的一次评估。")
    warnings.append("本次仅测意图识别与槽位解析；上下文由显式夹具提供，不代表完整客服业务链路成绩。")
    summary["warnings"] = warnings
    return summary


def write_report(directory, records, meta):
    summary = summarize(records, meta)
    (directory / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")
    with (directory / "failures.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["id", "difficulty", "status", "question", "expected", "actual",
                         "source", "intent_match", "slot_match", "source_match", "reason", "classification_id",
                         "http_status", "error_code", "api_message", "trace_id"])
        for r in records:
            if (r["status"] == "EVALUATED" and r["intent_match"]
                    and r.get("slot_match") is not False and r.get("source_match") is not False):
                continue
            obs = r.get("observed", {})
            cells = [r["id"], r["difficulty"], r["status"], r["question"],
                     "|".join(r["expected_intents"]), "|".join(i["code"] for i in obs.get("intents", [])),
                     obs.get("recognition", {}).get("source"), r.get("intent_match"),
                     r.get("slot_match"), r.get("source_match"), r.get("reason", ""), obs.get("classification_id")]
            cells += [r.get("error", {}).get(k) for k in ("http_status", "error_code", "api_message", "trace_id")]
            # 表格软件打开 CSV 时避免把用户测试文本当作公式执行。
            writer.writerow(["'" + c if isinstance(c, str) and c.startswith(("=", "+", "-", "@"))
                             else c for c in cells])
    return summary


def percent(value):
    return f"{100 * value:.2f}%" if value is not None else "N/A"


def print_summary(summary, directory):
    print(f"\n选定 {summary['selected']} 条；有效分类 {summary['evaluated']} 条；"
          f"完整匹配 {summary['exact_matches']} 条")
    print(f"有效样本匹配率：{percent(summary['accuracy_on_evaluated'])}；"
          f"覆盖率：{percent(summary['coverage'])}；"
          f"匹配数/选定总数：{percent(summary['matches_over_selected'])}")
    for source, values in summary["by_source"].items():
        print(f"  {source:16} {values['exact_matches']}/{values['evaluated']}  "
              f"{percent(values['accuracy_on_evaluated'])}")
    print(f"槽位匹配率：{percent(summary['slot_match']['accuracy'])}；"
          f"Micro-F1：{summary['micro_f1']}；Macro-F1：{summary['macro_f1']}")
    print("状态分布：" + json.dumps(summary["status_counts"], ensure_ascii=False))
    if summary["error_groups"]:
        print("错误分组：" + json.dumps(summary["error_groups"], ensure_ascii=False))
    for warning in summary["warnings"]:
        print("提示：" + warning)
    print("报告目录：" + str(directory))


class ScoringTests(unittest.TestCase):
    def obs(self, codes, slots=None):
        return {"intents": [{"code": c, "slots": (slots or [{}] * len(codes))[i]}
                            for i, c in enumerate(codes)], "recognition": {"source": "L3_LLM"}}

    def test_multiset_preserves_instances(self):
        case = {"expected_intents": ["ORDER_STATUS", "ORDER_STATUS"]}
        self.assertFalse(grade(case, self.obs(["ORDER_STATUS"]))["intent_match"])
        self.assertTrue(grade(case, self.obs(["ORDER_STATUS", "ORDER_STATUS"]))["intent_match"])

    def test_order_independent_and_extra_intent_fails(self):
        case = {"expected_intents": ["ORDER_STATUS", "WITHDRAW_RULE"]}
        self.assertTrue(grade(case, self.obs(["WITHDRAW_RULE", "ORDER_STATUS"]))["intent_match"])
        self.assertFalse(grade(case, self.obs(["WITHDRAW_RULE", "ORDER_STATUS", "OTHER"]))["intent_match"])

    def test_slots_match_distinct_instances_and_entities(self):
        labels = [{"code": "ORDER_STATUS", "slots": {"orderNo": v}} for v in ("A", "B")]
        wrong = self.obs(["ORDER_STATUS"] * 2, [{"orderNo": "A"}, {"orderNo": "A"}])["intents"]
        right = self.obs(["ORDER_STATUS"] * 2, [{"orderNo": "B"}, {"orderNo": "A"}])["intents"]
        self.assertFalse(slots_match(labels, wrong))
        self.assertTrue(slots_match(labels, right))
        self.assertFalse(slots_match(labels, right[:1]))

    def test_matching_backtracks(self):
        labels = [{"code": "ORDER_STATUS", "slots": {}},
                  {"code": "ORDER_STATUS", "slots": {"orderNo": "A"}}]
        actual = self.obs(["ORDER_STATUS"] * 2, [{"orderNo": "A"}, {"orderNo": "B"}])["intents"]
        self.assertTrue(slots_match(labels, actual))

    def test_empty_or_unknown_response_rejected(self):
        for master in ({}, {"intents": []}, self.obs(["MADE_UP"])):
            with self.assertRaises(ValueError):
                observation({**self.capabilities(), "master": master})

    def capabilities(self):
        return {"contractVersion": 1, "mode": "RECOGNITION_ONLY", "businessExecution": False,
                "contextMode": "EXPLICIT_FIXTURE", "mock": False}

    def test_master_string_and_object(self):
        master = self.obs(["OTHER"])
        self.assertEqual(observation({**self.capabilities(), "master": master}),
                         observation({**self.capabilities(), "master": json.dumps(master)}))

    def test_skip_not_in_accuracy_but_in_coverage(self):
        rows = [{"status": "EVALUATED", "intent_match": True}, {"status": "SKIPPED"},
                {"status": "ERROR"}, {"status": "NOT_RUN"}]
        metrics = group_metrics(rows)
        self.assertEqual(metrics["accuracy_on_evaluated"], 1)
        self.assertEqual(metrics["coverage"], .25)
        self.assertEqual(metrics["matches_over_selected"], .25)
        self.assertIsNone(group_metrics(rows[1:])["accuracy_on_evaluated"])

    def test_missing_fixtures_skipped_without_api(self):
        case = {"id": "test", "difficulty": "hard", "tags": [],
                "question": "{{ORDER_A}}", "expected_intents": ["ORDER_STATUS"]}
        self.assertEqual(evaluate_case(case, None, {})["status"], "SKIPPED")

    def test_rejects_full_workflow_response(self):
        with self.assertRaises(ValueError):
            observation({"master_result_json": self.obs(["OTHER"])})
        with self.assertRaises(ValueError):
            require_recognition_only({**self.capabilities(), "businessExecution": True})

    def test_client_whitelists_only_recognition_endpoints(self):
        client = Client("http://localhost:1", "dummy", 1)
        with self.assertRaises(ValueError):
            client.call("POST", "/api/agent/sessions")

    def test_only_one_recognition_call_even_for_context_and_forget(self):
        from unittest.mock import Mock
        client = Mock()
        client.recognize.return_value = self.obs(["MEMORY_FORGET"])
        case = {"id": "test", "difficulty": "hard", "tags": [],
                "question": "删除偏好", "expected_intents": ["MEMORY_FORGET"],
                "context": {"lastIntent": "ORDER_STATUS"},
                "history": [{"role": "USER", "content": "查订单"}]}
        record = evaluate_case(case, client, {})
        self.assertEqual(record["status"], "EVALUATED")
        self.assertTrue(record["intent_match"])
        client.recognize.assert_called_once_with(case["question"], case["context"], case["history"])
        self.assertEqual(len(client.mock_calls), 1)

    def test_http_error_details_are_preserved_in_case_record(self):
        from io import BytesIO
        from unittest.mock import Mock
        client = Client("http://localhost:1", "dummy", 1)
        payload = {"errorCode": "AGENT_OUTPUT_INVALID", "msg": "商品编号格式不正确",
                   "traceId": "trace-test-033", "data": {"private": "must-not-save"}}
        client.opener = Mock()
        client.opener.open.side_effect = urllib.error.HTTPError(
            "http://localhost:1", 422, "Invalid", {}, BytesIO(json.dumps(payload).encode()))
        case = {"id": "test", "difficulty": "easy", "tags": [],
                "question": "商品 ITEM-5842 有库存吗？", "expected_intents": ["PRE_SALE_PRODUCT"]}
        record = evaluate_case(case, client, {})
        self.assertEqual(record["status"], "ERROR")
        self.assertEqual(record["error"], {"http_status": 422, "error_code": "AGENT_OUTPUT_INVALID",
                                          "api_message": "商品编号格式不正确", "trace_id": "trace-test-033"})
        self.assertIn("商品编号格式不正确", record["reason"])
        self.assertNotIn("must-not-save", json.dumps(record))

    def test_error_fields_redact_echoed_credentials(self):
        token = "test-secret-admin-token"
        client = Client("http://localhost:1", token, 1)
        error = client.response_error("POST", ENDPOINT + "/recognize", 500, {
            "errorCode": "COMMON_SYSTEM_ERROR", "msg": "Bearer " + token + " password=secret-value",
            "traceId": "Bearer eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoxfQ.signature",
        })
        rendered = str(error) + json.dumps(error.details)
        for secret in (token, "secret-value", "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoxfQ.signature"):
            self.assertNotIn(secret, rendered)
        self.assertEqual(error.details["http_status"], 500)

    def test_non_json_gateway_error_keeps_status_without_html(self):
        from io import BytesIO
        from unittest.mock import Mock
        client = Client("http://localhost:1", "dummy", 1)
        client.opener = Mock()
        client.opener.open.side_effect = urllib.error.HTTPError(
            "http://localhost:1", 503, "Unavailable", {}, BytesIO(b"<html>proxy internals</html>"))
        with self.assertRaises(ApiError) as raised:
            client.call("GET", ENDPOINT)
        self.assertEqual(raised.exception.details, {"http_status": 503})
        self.assertNotIn("proxy internals", str(raised.exception))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cases", type=Path, default=ROOT / "intent_cases.json")
    parser.add_argument("--validate-only", action="store_true", help="只检查数据集，不调用后端")
    parser.add_argument("--self-test", action="store_true", help="运行内置判分自测，不调用后端")
    parser.add_argument("--base-url", default=os.environ.get("INTENT_EVAL_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--timeout", type=float, default=120, help="单次 HTTP 请求超时秒数")
    parser.add_argument("--limit", type=int, help="按文件顺序取前 N 条；小样本结果不能代表全量")
    parser.add_argument("--difficulty", action="append", choices=("easy", "medium", "hard"))
    parser.add_argument("--tag", action="append")
    parser.add_argument("--case-id", action="append")
    parser.add_argument("--fixture", action="append", default=[], metavar="KEY=VALUE")
    parser.add_argument("--output-dir", type=Path, help="必须为不存在的新目录，防止覆盖已有报告")
    args = parser.parse_args(argv)
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ScoringTests))
        return 0 if result.wasSuccessful() else 1
    try:
        raw = args.cases.read_bytes()
        data = json.loads(raw)
        cases = validate_dataset(data)
        if args.validate_only:
            print(f"校验通过：{len(cases)} 条用例；覆盖 {len({c for row in cases for c in row['expected_intents']})} 种意图")
            print("难度分布：" + str(dict(collections.Counter(c["difficulty"] for c in cases))))
            print(f"上下文快照用例：{sum(bool(c.get('context') or c.get('history')) for c in cases)}；"
                  f"多意图金标：{sum(len(c['expected_intents']) > 1 for c in cases)}")
            return 0
        if args.timeout <= 0 or (args.limit is not None and args.limit <= 0):
            raise ValueError("timeout 与 limit 必须大于 0")
        if args.case_id and set(args.case_id) - {c["id"] for c in cases}:
            raise ValueError("case-id 包含不存在的用例")
        cases = [c for c in cases if (not args.difficulty or c["difficulty"] in args.difficulty)
                 and (not args.tag or set(args.tag) & set(c["tags"]))
                 and (not args.case_id or c["id"] in args.case_id)]
        if args.limit:
            cases = cases[:args.limit]
        if not cases:
            raise ValueError("筛选结果为空")
        fixtures = dict(data.get("fixture_defaults", {}))
        fixtures.update(object_json(os.environ.get("INTENT_EVAL_FIXTURES", "{}"), "INTENT_EVAL_FIXTURES"))
        for item in args.fixture:
            key, sep, value = item.partition("=")
            if not sep:
                raise ValueError("fixture 格式应为 KEY=VALUE")
            fixtures[key] = value
        for key, value in fixtures.items():
            if key not in data.get("fixtures", {}) or not isinstance(value, str) or not value.strip():
                raise ValueError(f"fixture {key} 未声明或不是非空字符串")
            pattern = (r"(?:SO|TR|T)\d{6,40}" if key.startswith("ORDER_") else
                       r"(?:DSP|DP|D)\d{6,40}" if key.startswith("DISPUTE_") else r"\d{1,10}")
            if not re.fullmatch(pattern, value, flags=re.IGNORECASE):
                raise ValueError(f"fixture {key} 格式不符合当前后端编号规则")
        for a, b in (("ORDER_A", "ORDER_B"), ("DISPUTE_A", "DISPUTE_B"), ("ITEM_A", "ITEM_B")):
            if a in fixtures and b in fixtures and fixtures[a] == fixtures[b]:
                raise ValueError(f"{a} 和 {b} 必须是不同对象")
        parsed = urllib.parse.urlsplit(args.base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.query or parsed.fragment or parsed.username:
            raise ValueError("base-url 应为不含凭证、query 或 fragment 的 HTTP(S) 服务地址")
        admin_token = os.environ.get("INTENT_EVAL_ADMIN_TOKEN", "")
        if not admin_token.strip():
            raise ValueError("请设置 INTENT_EVAL_ADMIN_TOKEN（管理员 JWT）；无需普通用户 JWT 或模型 API Key")
        client = Client(args.base_url, admin_token, args.timeout)
        capabilities = require_recognition_only(client.call("GET", ENDPOINT))
        directory = (args.output_dir or ROOT / "outputs" /
                     (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])).resolve()
        directory.mkdir(parents=True, exist_ok=False)
    except (ValueError, KeyError, TypeError, OSError, ApiError) as exc:
        parser.error(str(exc))
    meta = {"started_at": utc_now(), "dataset_sha256": hashlib.sha256(raw).hexdigest(),
            "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "dataset_case_count": data["case_count"], "selected_ids": [c["id"] for c in cases],
            "base_url": args.base_url, "mode": "RECOGNITION_ONLY", "capabilities": capabilities,
            "provided_fixture_names": sorted(fixtures), "timeout_seconds": args.timeout}
    records = []
    with (directory / "results.jsonl").open("w", encoding="utf-8") as output:
        output.write(json.dumps({"type": "metadata", **meta}, ensure_ascii=False) + "\n")
        output.flush()
        try:
            for case in cases:
                record = evaluate_case(case, client, fixtures)
                records.append(record)
                output.write(json.dumps({"type": "case", **record}, ensure_ascii=False) + "\n")
                output.flush()
                label = ("PASS" if record["intent_match"] else "MISMATCH") if record["status"] == "EVALUATED" else record["status"]
                print(f"[{len(records):3}/{len(cases)}] {record['id']} {label}", flush=True)
                if record.get("reason"):
                    print("    " + record["reason"], flush=True)
                if record["status"] == "INTERRUPTED":
                    break
        except KeyboardInterrupt:
            pass
        finally:
            # 未开始的用例也保留在分母中，不能因中断而抬高覆盖率。
            for case in cases[len(records):]:
                record = {"id": case["id"], "difficulty": case["difficulty"], "tags": case["tags"],
                          "question": case["question"], "expected_intents": case["expected_intents"],
                          "context_mode": "context_fixture" if case.get("context") or case.get("history") else "single_turn",
                          "status": "NOT_RUN", "reason": "批次提前结束"}
                records.append(record)
                output.write(json.dumps({"type": "case", **record}, ensure_ascii=False) + "\n")
            summary = write_report(directory, records, meta)
    print_summary(summary, directory)
    return 1 if (summary["evaluated"] != summary["selected"]
                 or summary["fallback_reasons"].get("MOCK_CLASSIFICATION")
                 or summary["mock_detected"]) else 0


if __name__ == "__main__":
    sys.exit(main())
