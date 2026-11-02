#!/usr/bin/env python3
"""Fail closed on OSV lookup errors or newly undispositioned Maven/PyPI findings.

This is a coordinate advisory lookup, not a scanner for arbitrary ELF bytes. Native
versions and producer provenance require the separate release security review.
Only the Python distributions listed in runtime-advisory-scope.json are queried.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

API = "https://api.osv.dev/v1/"
LIMIT = 16 * 1024 * 1024


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def request_json(endpoint, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(API + endpoint, data=data,
                                     headers={"Content-Type": "application/json",
                                              "User-Agent": "dosos73-release-audit/1"})
    with urllib.request.urlopen(request, timeout=45) as response:
        body = response.read(LIMIT + 1)
    if len(body) > LIMIT:
        raise ValueError("OSV response exceeded allocation limit")
    return json.loads(body)


def queries_for(inventory, scope):
    if not isinstance(inventory, list) or not inventory:
        raise ValueError("Empty or invalid Maven inventory")
    queries = []
    for item in inventory:
        group, name, version = (item.get(k) for k in ("group", "name", "version"))
        if not all(isinstance(s, str) and s.strip() for s in (group, name, version)):
            raise ValueError("Invalid Maven identity")
        queries.append({"package": {"ecosystem": "Maven", "name": group + ":" + name},
                        "version": version})
    for item in scope["pypi"]:
        if not item.get("name") or not item.get("version") or not item.get("evidence"):
            raise ValueError("Python advisory identity needs version and evidence")
        queries.append({"package": {"ecosystem": "PyPI", "name": item["name"]},
                        "version": item["version"]})
    # A duplicate resolved artifact is one coordinate lookup, not an extra test.
    return list({json.dumps(q, sort_keys=True): q for q in queries}.values())


def approved(query, vuln_id, dispositions, today):
    for item in dispositions:
        if (item.get("id") != vuln_id or item.get("package") != query["package"] or
                item.get("version") != query["version"]):
            continue
        if (item.get("status") not in ("not_affected", "accepted_risk") or
                not all(item.get(k) for k in ("owner", "reason", "evidence", "expires"))):
            raise ValueError("Incomplete vulnerability disposition")
        expiry = dt.date.fromisoformat(item["expires"])
        if expiry >= today:
            return item
    return None


def audit(queries, dispositions, today, transport=request_json):
    result = transport("querybatch", {"queries": queries})
    results = result.get("results")
    if not isinstance(results, list) or len(results) != len(queries):
        raise ValueError("Incomplete OSV batch response")
    findings, details = [], {}
    for query, response in zip(queries, results):
        if not isinstance(response, dict) or response.get("error") or response.get("next_page_token"):
            raise ValueError("Invalid or paginated OSV batch response; scan incomplete")
        for match in response.get("vulns", []):
            vuln_id = match.get("id")
            if not isinstance(vuln_id, str) or not vuln_id or "/" in vuln_id:
                raise ValueError("Invalid advisory ID")
            if vuln_id not in details:
                details[vuln_id] = transport("vulns/" + vuln_id)
                if details[vuln_id].get("id") != vuln_id:
                    raise ValueError("OSV advisory identity mismatch")
            disposition = approved(query, vuln_id, dispositions, today)
            findings.append({"query": query, "id": vuln_id, "disposition": disposition,
                             "result": "reviewed" if disposition else "BLOCKED_UNREVIEWED"})
    return {"queries": queries, "query_count": len(queries), "findings": findings,
            "advisories": details,
            "result": "PASS_SCOPED_LOOKUP" if all(f["disposition"] for f in findings)
            else "BLOCKED_UNREVIEWED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--scope", type=Path, default=Path("docs/audit/runtime-advisory-scope.json"))
    parser.add_argument("--dispositions", type=Path, default=Path("docs/audit/advisory-dispositions.json"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = {"checked_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
              "scope": "OSV Maven/PyPI coordinate lookup only; excludes native/system advisory completeness",
              "inventory_sha256": hashlib.sha256(args.inventory.read_bytes()).hexdigest(),
              "runtime_scope_sha256": hashlib.sha256(args.scope.read_bytes()).hexdigest()}
    try:
        report.update(audit(queries_for(read_json(args.inventory), read_json(args.scope)),
                            read_json(args.dispositions)["dispositions"], dt.date.today()))
        code = 0 if report["result"] == "PASS_SCOPED_LOOKUP" else 1
    except Exception as error:
        # API/parse errors cannot turn a failed/incomplete scan into a green CI gate.
        report.update(result="ERROR_INCOMPLETE_SCAN", error_type=type(error).__name__)
        code = 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Advisory lookup: {report['result']}; queries={report.get('query_count', 0)}; "
          f"findings={len(report.get('findings', []))}; report={args.output}")
    return code


if __name__ == "__main__":
    sys.exit(main())
