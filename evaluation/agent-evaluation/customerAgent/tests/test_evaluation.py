"""Offline adversarial tests for the evaluator, not claims about the live Agent."""
import copy
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from customer_eval.client import HttpClient, NoRedirect
from customer_eval.common import Redactor, check, decode_columns, get_path, load_env, outcome, read_json, read_jsonl, render, write_json, write_jsonl
from customer_eval.dataset import validate
from customer_eval.grading import DISPUTE_FIELDS, ORDER_FIELDS, evaluate_turn, match_instances
from customer_eval.judge import Judge
from customer_eval.report import instance_metrics, summarize
from customer_eval.runner import Runner
from build_datasets import functional, l2_cases
from compare_runs import compare
from prepare_l2_release import prepare


def fixture():
    f = read_json(ROOT / "fixtures/template.json")
    for key in ["orders", "disputes", "knowledge", "actors"]:
        for item in f[key].values():
            item["ready"] = True
    return f


def envelope(value, status=200, error=None):
    return {"http_status": status, "error": None, "body": {"code": status, "data": value, "errorCode": error}, "elapsed_ms": 10}


def order_record(two=False):
    f = fixture()
    tasks, results, tools, expected = [], [], [], []
    for index, key in enumerate(["a", "b"] if two else ["a"], 1):
        instance_id = f"AR1:i{index}"
        fact = {k: v for k, v in f["orders"][key].items() if k in ORDER_FIELDS}
        tasks.append({"intentInstanceId": instance_id, "code": "ORDER_STATUS", "resolutionState": "COMPLETED", "slots": {"orderNo": fact["orderNo"]}, "missingSlots": []})
        results.append({"intentInstanceId": instance_id, "code": "ORDER_STATUS", "agentName": "ORDER_AGENT", "resolutionState": "COMPLETED", "answer": fact["orderNo"] + " 已查询", "evidence": [fact], "citations": [], "bizType": "ORDER", "bizNo": fact["orderNo"]})
        tools.append({"tool_name": "queryOrder", "agent_name": "ORDER_AGENT", "success": 1, "input_json": {"intentInstanceId": instance_id, "arguments": {"orderNo": fact["orderNo"]}}, "output_json": fact})
        expected.append({"alias": key, "code": "ORDER_STATUS", "state": "COMPLETED", "slots": {"orderNo": fact["orderNo"]}, "fact_refs": ["orders." + key], "tools": [{"name": "queryOrder", "arguments": {"orderNo": fact["orderNo"]}}]})
    answer = {"schemaVersion": 2, "runNo": "AR1", "traceId": "trace1", "answer": "已查询", "handoffRequired": False, "intentResults": results, "citations": [], "suggestedActions": []}
    record = {"actor_ref": "buyer_a", "response": envelope(answer), "trace": {"run_no": "AR1", "provider": "MOCK", "master_result_json": {"recognition": {"source": "L3_LLM"}, "intents": tasks}}, "tools": tools}
    turn = {"content": "查询两笔订单", "expected": {"agent_response": True, "instances": expected, "handoff": False}}
    return f, turn, record


class DatasetTests(unittest.TestCase):
    def test_dataset_size_splits_and_deterministic_rebuild(self):
        rows = read_jsonl(ROOT / "datasets/functional.jsonl")
        self.assertEqual(rows, functional())
        self.assertEqual(validate(rows, fixture())["cases"], 240)
        self.assertEqual(sum(r["split"] == "dev" for r in rows), 120)
        self.assertEqual(sum(len(r["turns"]) for r in rows), 283)

    def test_l2_payload_is_valid_and_never_silently_truncated(self):
        rows = l2_cases()
        codes = sorted({r["expectedIntent"] for r in rows} - {"OTHER"})
        self.assertEqual(len(prepare(rows, codes)["evaluations"]), 180)
        with self.assertRaises(ValueError):
            prepare(rows * 2, codes)
        with self.assertRaises(ValueError):
            prepare(rows, codes, [rows[0]["query"]])
        with self.assertRaises(ValueError):
            prepare(rows, codes[:-1])

    def test_unknown_assertion_fails_closed(self):
        rows = copy.deepcopy(functional()[:1])
        rows[0]["turns"][0]["expected"]["wrongly_spelled_check"] = True
        with self.assertRaises(ValueError):
            validate(rows, fixture())

    def test_ready_fixture_requires_independent_full_fact(self):
        f = fixture(); del f["orders"]["a"]["deliveryStatus"]
        with self.assertRaises(ValueError):
            validate(functional(), f)

    def test_family_cannot_cross_holdout(self):
        rows = copy.deepcopy(functional()[:2]); rows[1]["split"] = "holdout"
        with self.assertRaises(ValueError):
            validate(rows, fixture())

    def test_fixture_substitution_preserves_types_and_requires_paths(self):
        self.assertEqual(render("{{orders.a.itemId}}", fixture()), 10001)
        with self.assertRaises(ValueError):
            render("{{missing.value}}", fixture())


class GraderTests(unittest.TestCase):
    def grade(self, f, turn, record, aliases=None):
        return evaluate_turn(turn, record, f, {} if aliases is None else aliases, "mock", [])

    def test_correct_two_instances_pass(self):
        f, turn, record = order_record(True)
        self.assertEqual(outcome(self.grade(f, turn, record)), "PASS")

    def test_same_code_missing_second_order_is_not_a_pass(self):
        f, turn, record = order_record(True)
        record["trace"]["master_result_json"]["intents"].pop()
        record["response"]["body"]["data"]["intentResults"].pop()
        self.assertEqual(outcome(self.grade(f, turn, record)), "FAIL")

    def test_array_order_does_not_change_instance_binding(self):
        f, turn, record = order_record(True)
        record["trace"]["master_result_json"]["intents"].reverse()
        aliases = {}
        self.assertEqual(outcome(self.grade(f, turn, record, aliases)), "PASS")
        self.assertEqual(aliases, {"a": "AR1:i1", "b": "AR1:i2"})

    def test_cross_order_evidence_is_hard_failure(self):
        f, turn, record = order_record(True)
        results = record["response"]["body"]["data"]["intentResults"]
        results[0]["evidence"], results[1]["evidence"] = results[1]["evidence"], results[0]["evidence"]
        self.assertTrue(any(c["hard"] and c["status"] == "FAIL" for c in self.grade(f, turn, record)))

    def test_correct_answer_with_wrong_tool_fact_fails(self):
        f, turn, record = order_record()
        record["tools"][0]["output_json"] = dict(record["tools"][0]["output_json"], deliveryStatus="WRONG")
        self.assertEqual(outcome(self.grade(f, turn, record)), "FAIL")

    def test_foreign_owner_is_not_bypassed_by_valid_fact(self):
        f, turn, record = order_record()
        f["orders"]["a"]["owners"] = ["unrelated_b"]
        self.assertTrue(any(c["check_id"] == "order_fact_owner" and c["status"] == "FAIL" for c in self.grade(f, turn, record)))

    def test_missing_trace_or_tools_is_not_evaluated(self):
        for key in ["trace", "tools"]:
            f, turn, record = order_record(); record[key] = None
            self.assertEqual(outcome(self.grade(f, turn, record)), "NOT_EVALUATED")

    def test_http_200_with_partial_failure_is_not_success(self):
        f, turn, record = order_record()
        record["response"]["body"]["data"]["intentResults"][0]["resolutionState"] = "FAILED"
        self.assertEqual(outcome(self.grade(f, turn, record)), "FAIL")

    def test_invalid_nested_payload_is_failed_not_exception(self):
        f, turn, record = order_record()
        record["response"]["body"]["data"]["intentResults"][0]["evidence"] = None
        self.assertEqual(outcome(self.grade(f, turn, record)), "FAIL")

    def test_unauthorized_tool_hard_fails(self):
        f, turn, record = order_record()
        record["tools"].append({"tool_name": "refund", "agent_name": "ORDER_AGENT", "input_json": {}, "output_json": {}})
        self.assertTrue(any(c["check_id"] == "tool_capabilities" and c["status"] == "FAIL" for c in self.grade(f, turn, record)))

    def test_resume_requires_same_instance_id(self):
        expected = [{"alias": "a", "code": "ORDER_STATUS", "slots": {}, "resume": True}]
        self.assertIsNone(match_instances(expected, [{"code": "ORDER_STATUS", "slots": {}, "intentInstanceId": "new"}], {"a": "old"}))

    def test_handoff_text_without_database_record_not_pass(self):
        f, turn, record = order_record()
        record["response"]["body"]["data"].update(handoffRequired=True, handoffNo="AH1")
        turn["expected"]["handoff"] = True
        self.assertEqual(outcome(self.grade(f, turn, record)), "NOT_EVALUATED")

    def test_expected_401_is_success_but_200_is_not(self):
        turn = {"expected": {"http_status": [401], "no_business_facts": True}}
        record = {"response": envelope(None, 401), "actor_ref": "anonymous"}
        self.assertEqual(outcome(self.grade(fixture(), turn, record)), "PASS")
        record["response"] = envelope({})
        self.assertEqual(outcome(self.grade(fixture(), turn, record)), "FAIL")

    def test_secret_marker_scored_before_artifact_redaction(self):
        f, turn, record = order_record()
        record["response"]["body"]["data"]["answer"] = f["forbidden_literals"][0]
        self.assertEqual(outcome(self.grade(f, turn, record)), "FAIL")
        self.assertNotIn(f["forbidden_literals"][0], json.dumps(Redactor(f["forbidden_literals"])(record)))

    def test_missing_probe_is_not_a_fake_zero_call(self):
        f, turn, record = order_record(); turn["expected"]["required_probes"] = ["model_calls"]
        self.assertEqual(outcome(self.grade(f, turn, record)), "NOT_EVALUATED")

    def test_nested_extra_private_dto_field_fails(self):
        f, turn, record = order_record()
        record["response"]["body"]["data"]["intentResults"][0]["evidence"][0]["buyerPhone"] = "private-phone"
        self.assertTrue(any(c["check_id"] == "order_dto_fields" and c["status"] == "FAIL" for c in self.grade(f, turn, record)))

    def test_fabricated_or_unaggregated_citation_is_rejected(self):
        f, turn, record = order_record()
        c = {"sourceNo": "S1", "docNo": "FAKE", "docVersion": 1, "chunkNo": "FAKE-P1", "content": "无依据规则", "intentInstanceId": "AR1:i1"}
        record["response"]["body"]["data"]["citations"] = [c]
        checks = self.grade(f, turn, record)
        self.assertTrue(any(c["check_id"] == "citation_aggregation" and c["status"] == "FAIL" for c in checks))
        self.assertTrue(any(c["check_id"] == "citation_from_tool" and c["status"] == "FAIL" for c in checks))

    def test_unauthorized_action_link_rejected(self):
        f, turn, record = order_record()
        record["response"]["body"]["data"]["suggestedActions"] = [{"type": "VIEW_ORDER", "target": "https://evil.invalid", "intentInstanceId": "AR1:i1", "bizNo": f["orders"]["a"]["orderNo"]}]
        self.assertEqual(outcome(self.grade(f, turn, record)), "FAIL")

    def test_trace_json_string_decoding_and_corruption(self):
        self.assertEqual(decode_columns({"input_json": '{"a":1}'})["input_json"], {"a": 1})
        with self.assertRaises(ValueError):
            decode_columns({"input_json": "not json"})


class JudgeTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"CUSTOMER_EVAL_JUDGE_URL": "https://judge.invalid/v1/chat/completions", "CUSTOMER_EVAL_JUDGE_MODEL": "test-model", "CUSTOMER_EVAL_JUDGE_API_KEY": "fake-test-key"})
        self.env.start(); self.addCleanup(self.env.stop)
        self.judge = Judge("auto", Redactor(["fake-test-key"]))
        self.f, self.turn, self.record = order_record()
        self.turn["expected"]["knowledge_ref"] = "knowledge.platform"

    def response(self, content):
        return {"http_status": 200, "error": None, "elapsed_ms": 5, "body": {"choices": [{"message": {"content": json.dumps(content)}}]}}

    def test_offline_disabled_judge_never_makes_request(self):
        self.judge.mode = "off"
        with patch.object(HttpClient, "request_url", side_effect=AssertionError("network forbidden")):
            self.assertEqual(self.judge.score(self.turn, self.record, self.f, [])[0]["status"], "NOT_EVALUATED")

    def test_valid_judge_scores_actual_answer(self):
        verdict = {"status": "SCORED", "grounding": 2, "coverage": 2, "actionability": 2, "clarity": 2, "unsupported_claims": [], "missing_requirements": [], "evidence_refs": ["1"], "reason": "依据完整"}
        with patch.object(HttpClient, "request_url", return_value=self.response(verdict)) as request:
            self.assertEqual(self.judge.score(self.turn, self.record, self.f, [])[0]["status"], "PASS")
            self.assertIn('"answer": "已查询"', request.call_args.args[3]["messages"][1]["content"])

    def test_invalid_or_invented_judge_evidence_is_unscored(self):
        for verdict in [{}, {"status": "SCORED", "grounding": True}, {"status": "INSUFFICIENT_EVIDENCE"}]:
            with patch.object(HttpClient, "request_url", return_value=self.response(verdict)):
                self.assertEqual(self.judge.score(self.turn, self.record, self.f, [])[0]["status"], "NOT_EVALUATED")

    def test_judge_cannot_invent_reference(self):
        verdict = {"status": "SCORED", "grounding": 2, "coverage": 2, "actionability": 2, "clarity": 2, "unsupported_claims": [], "missing_requirements": [], "evidence_refs": ["nonexistent"], "reason": "all correct"}
        with patch.object(HttpClient, "request_url", return_value=self.response(verdict)):
            self.assertEqual(self.judge.score(self.turn, self.record, self.f, [])[0]["status"], "NOT_EVALUATED")


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.gates = read_json(ROOT / "config/gates.json")

    def trial(self, status, number=1, checks=None):
        return {"case_id": "c", "category": "order", "trial": number, "status": status, "checks": checks or [check("x", status == "PASS")], "turns": []}

    def test_missing_results_do_not_count_as_pass(self):
        t = self.trial("NOT_EVALUATED", checks=[check("trace", None)])
        result = summarize([t], self.gates, 1)
        self.assertEqual(result["status"], "NOT_EVALUATED")
        self.assertEqual(result["task_success_rate"], 0)
        self.assertEqual(result["check_coverage"], 0)

    def test_hard_failure_overrides_high_average(self):
        rows = [dict(self.trial("PASS"), case_id=str(i)) for i in range(99)]
        rows += [dict(self.trial("FAIL", checks=[check("security", False, hard=True)]), case_id="bad")]
        self.assertEqual(summarize(rows, self.gates, 1)["status"], "FAIL")

    def test_all_three_is_different_from_at_least_one(self):
        result = summarize([self.trial("PASS", 1), self.trial("FAIL", 2), self.trial("PASS", 3)], self.gates, 3)
        self.assertEqual(result["repetition"]["at_least_one_pass"], 1)
        self.assertEqual(result["repetition"]["all_k_pass"], 0)

    def test_two_passes_cannot_claim_all_three(self):
        result = summarize([self.trial("PASS", 1), self.trial("PASS", 2)], self.gates, 3)
        self.assertEqual(result["status"], "NOT_EVALUATED")
        self.assertEqual(result["repetition"]["all_k_pass"], 0)

    def test_empty_suite_not_success(self):
        self.assertEqual(summarize([], self.gates, 1)["status"], "NOT_EVALUATED")

    def test_full_release_never_claimed_by_api_only(self):
        result = summarize([self.trial("PASS")], self.gates, 1)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["release_status"], "NOT_EVALUATED")

    def test_f1_counts_two_instances_of_same_code(self):
        f, turn, record = order_record(True)
        record["expected"] = turn["expected"]
        record["trace"]["master_result_json"]["intents"].pop()
        metrics = instance_metrics([{"turns": [record]}])
        self.assertEqual(metrics["micro"]["tp"], 1)
        self.assertEqual(metrics["micro"]["fn"], 1)
        self.assertAlmostEqual(metrics["micro"]["f1"], 2 / 3)

    def test_missing_trace_does_not_have_perfect_f1(self):
        f, turn, record = order_record()
        record["expected"] = turn["expected"]; record["trace"] = None
        metrics = instance_metrics([{"turns": [record]}])
        self.assertIsNone(metrics["micro"]["f1"])
        self.assertEqual(metrics["coverage"], 0)

    def test_malformed_master_still_produces_failure_report(self):
        f, turn, record = order_record()
        record["expected"] = turn["expected"]
        record["trace"]["master_result_json"] = ["bad"]
        record["checks"] = [check("master_schema", False)]
        trial = dict(self.trial("FAIL"), turns=[record])
        result = summarize([trial], self.gates, 1)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["routed_instance_metrics"]["coverage"], 0)

    def test_comparison_requires_same_fixture_and_complete_trials(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            manifest = {"case_ids": ["c"], "trials_per_case": 1, "fixture_hash": "a"}
            for folder in [a, b]:
                write_json(folder / "manifest.json", manifest)
                write_jsonl(folder / "raw-results.jsonl", [self.trial("PASS")])
            self.assertEqual(compare(a, b)["counts"], {"both_pass": 1})
            write_json(b / "manifest.json", dict(manifest, fixture_hash="b"))
            with self.assertRaises(ValueError):
                compare(a, b)


class UtilityTests(unittest.TestCase):
    def test_env_is_literal_and_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {"EVAL_EXISTING": "original"}):
            p = Path(tmp) / ".env"
            p.write_text("EVAL_EXISTING=changed\nEVAL_LITERAL='$(touch /tmp/do-not-create-eval-file)'\n")
            load_env(p)
            self.assertEqual(os.environ["EVAL_EXISTING"], "original")
            self.assertTrue(os.environ["EVAL_LITERAL"].startswith("$(touch"))

    def test_credentials_redacted_at_all_nesting_levels(self):
        value = {"nested": [{"authorization": "Bearer secret"}, {"text": "fake-test-key"}]}
        encoded = json.dumps(Redactor(["fake-test-key"])(value))
        self.assertNotIn("secret", encoded); self.assertNotIn("fake-test-key", encoded)

    def test_redirects_are_not_followed(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "", {}, "https://evil.invalid"))

    def test_http_error_response_is_parsed_without_retry(self):
        response = io.BytesIO(b'{"code":403,"data":null,"errorCode":"DENIED"}')
        response.code = 403
        with patch("urllib.request.OpenerDirector.open", return_value=response) as send:
            result = HttpClient("http://localhost:8085").request("GET", "/test")
        self.assertEqual(result["http_status"], 403); self.assertEqual(send.call_count, 1)

    def test_transport_error_has_no_exception_secrets(self):
        with patch("urllib.request.OpenerDirector.open", side_effect=OSError("fake-secret")):
            result = HttpClient("http://localhost:8085").request("GET", "/test")
        self.assertNotIn("fake-secret", json.dumps(result))

    def test_url_cannot_contain_secret_or_redirect_path(self):
        with self.assertRaises(ValueError):
            HttpClient("https://user:secret@example.invalid")
        with self.assertRaises(ValueError):
            HttpClient("http://localhost").request("GET", "//evil.invalid/path")

    def test_cli_validate_and_missing_credentials_run(self):
        script = ROOT / "scripts/run_evaluation.py"
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run([sys.executable, str(script), "--case-id", "GREETING-001", "--output", str(Path(tmp) / "out")], capture_output=True, text=True, env={"PATH": os.environ.get("PATH", "")})
            self.assertEqual(result.returncode, 2, result.stderr)
            report = read_json(Path(tmp) / "out/summary.json")
            self.assertEqual(report["counts"]["NOT_EVALUATED"], 1)


class RunnerTests(unittest.TestCase):
    def test_missing_fixture_is_not_sent(self):
        f = fixture(); f["orders"]["a"]["ready"] = False
        runner = Runner(None, f, "mock", Judge("off", Redactor()), interval=0)
        with patch.dict(os.environ, {"CUSTOMER_EVAL_BUYER_TOKEN": "test-token"}):
            case = next(c for c in functional() if c["case_id"] == "ORDER-001")
            result = runner.run(case, 1)
        self.assertEqual(result["status"], "NOT_EVALUATED")
        self.assertEqual(result["turns"], [])

    def test_existing_http_and_string_trace_columns_are_used(self):
        f, turn, rec = order_record()
        class FakeClient:
            def __init__(self):
                self.calls = []
            def request(self, method, path, token="", body=None, headers=None):
                self.calls.append((method, path, body))
                if path == "/api/agent/sessions":
                    return envelope({"sessionNo": "AS1"})
                if path.endswith("/messages"):
                    return rec["response"]
                if path.endswith("/tool-calls"):
                    return envelope([dict(t, input_json=json.dumps(t["input_json"]), output_json=json.dumps(t["output_json"])) for t in rec["tools"]])
                return envelope(dict(rec["trace"], master_result_json=json.dumps(rec["trace"]["master_result_json"])))
        client = FakeClient()
        runner = Runner(client, f, "mock", Judge("off", Redactor()), interval=0)
        case = {"case_id": "test", "category": "order", "split": "dev", "actor_ref": "buyer_a", "requires": [], "turns": [turn]}
        with patch.dict(os.environ, {"CUSTOMER_EVAL_BUYER_TOKEN": "buyer-token", "CUSTOMER_EVAL_ADMIN_TOKEN": "admin-token"}):
            first, second = runner.run(case, 1), runner.run(case, 2)
        self.assertEqual(first["status"], "PASS")
        ids = [body["clientMessageId"] for _, path, body in client.calls if path.endswith("/messages")]
        self.assertNotEqual(ids[0], ids[1])
        self.assertEqual(first["turns"][0]["tools"][0]["input_json"]["arguments"]["orderNo"], f["orders"]["a"]["orderNo"])


if __name__ == "__main__":
    unittest.main()
