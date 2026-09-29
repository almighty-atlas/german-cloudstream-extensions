#!/usr/bin/env python3
"""Classify live test results and reject incomplete or skipped runs."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

expected = {
    "com.germanstreams.FilmoLiveTest": 4,
    "com.germanstreams.SerienStreamLiveTest": 5,
}
selector_pattern = re.compile(
    r"parsed to nothing|selectors are stale|metadata is stale|season nav is stale|"
    r"episode rows are stale|dub markers are stale|link resolution is dead|"
    r"play URLs no longer carry a token|grid is stale|pager is stale|"
    r"rating line is stale|Verwandte Filme.*stale|provider chips",
    re.IGNORECASE,
)

files = sorted(Path("smoke/build/test-results/test").glob("*.xml"))
counts = {name: 0 for name in expected}
skipped = 0
failures = []
invalid = False

for path in files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"Invalid test report {path}: {exc}", file=sys.stderr)
        invalid = True
        continue
    for case in root.iter("testcase"):
        name = case.get("classname", "")
        if name in counts:
            counts[name] += 1
            skipped += len(case.findall("skipped"))
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                failures.append(failure.get("message", "") + "\n" + (failure.text or ""))

complete = bool(files) and not invalid and skipped == 0 and all(
    counts[name] >= minimum for name, minimum in expected.items()
)
status = int(sys.argv[1])
if status == 0 and complete and not failures:
    verdict = "pass"
elif any(selector_pattern.search(message) for message in failures):
    verdict = "selectors"
else:
    verdict = "infra"

print(f"verdict={verdict}")
print(f"Live tests: {counts}; skipped: {skipped}; reports: {len(files)}", file=sys.stderr)
if not complete:
    print("Live test run incomplete; refusing a green result.", file=sys.stderr)
