#!/usr/bin/env python3
"""Execute existing customer Java contracts and map fresh Surefire evidence.

This covers T0 only. External fault scenarios remain NOT_EVALUATED.
"""
import argparse
import datetime as dt
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from customer_eval.common import ROOT, read_jsonl, write_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--online", action="store_true", help="allow Maven to resolve missing dependencies (default offline)")
    p.add_argument("--list", action="store_true", help="list contract/fault scenarios without executing Maven")
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    scenarios = read_jsonl(ROOT / "datasets/contracts-and-faults.jsonl")
    if args.list:
        for s in scenarios:
            print(s["case_id"], s["execution"], s["description"])
        return 0
    folder = args.output or ROOT / "outputs" / ("contracts-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    folder.mkdir(parents=True, exist_ok=False)
    repo = ROOT.parents[2]
    cmd = ["mvn"] + ([] if args.online else ["-o"]) + ["-pl", "mall-agent-service,mall-order-service", "-am", "test",
           "-Dtest=Customer*Test,IntentReleaseTest,AgentOrderQueryServiceTest", "-Dsurefire.failIfNoSpecifiedTests=false"]
    started = time.time()
    with (folder / "maven.log").open("w", encoding="utf-8") as log:
        try:
            process = subprocess.run(cmd, cwd=repo, stdout=log, stderr=subprocess.STDOUT, timeout=900)
            returncode = process.returncode
        except (OSError, subprocess.TimeoutExpired):
            returncode = None
    evidence = {}
    for module in ["mall-agent-service", "mall-order-service"]:
        for path in (repo / module / "target/surefire-reports").glob("TEST-*.xml"):
            if path.stat().st_mtime < started:
                continue  # never reuse a stale passing test report
            xml = ET.parse(path)
            for case in xml.iter("testcase"):
                key = case.get("classname", "").split(".")[-1] + "#" + case.get("name", "")
                status = "FAIL" if case.find("failure") is not None or case.find("error") is not None else "NOT_EVALUATED" if case.find("skipped") is not None else "PASS"
                evidence[key] = {"status": status, "report": str(path.relative_to(repo)), "seconds": case.get("time")}
    results = []
    for scenario in scenarios:
        e = evidence.get(scenario.get("test_selector"))
        results.append(dict(scenario, status=e["status"] if e else "NOT_EVALUATED", evidence=e))
    unit = [s for s in results if s["execution"] == "junit"]
    status = "FAIL" if returncode not in (0, None) or any(s["status"] == "FAIL" for s in unit) else "PASS" if returncode == 0 and all(s["status"] == "PASS" for s in unit) else "NOT_EVALUATED"
    write_json(folder / "summary.json", {"scope": "T0 customer Java contracts", "status": status, "maven_exit_code": returncode,
                                        "full_fault_suite_status": "NOT_EVALUATED", "scenarios": results})
    print(f"T0: {status}; full fault suite: NOT_EVALUATED; report: {folder / 'summary.json'}")
    return {"PASS": 0, "FAIL": 1, "NOT_EVALUATED": 2}[status]


if __name__ == "__main__":
    raise SystemExit(main())
