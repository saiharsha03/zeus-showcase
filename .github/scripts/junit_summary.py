"""Markdown summary of JUnit XML results for $GITHUB_STEP_SUMMARY.

    python3 .github/scripts/junit_summary.py "<title>" <dir-or-file> [...]

Prints totals, then up to 30 failures with the first line of each message. Exit code is always 0; the test step
itself decides pass or fail.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def files(paths):
    for p in map(Path, paths):
        if p.is_file():
            yield p
        elif p.is_dir():
            yield from sorted(p.rglob("*.xml"))


def main():
    title, paths = sys.argv[1], sys.argv[2:]
    total = failed = errors = skipped = 0
    seconds = 0.0
    failures = []
    for f in files(paths):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
            total += int(suite.get("tests", 0))
            failed += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            seconds += float(suite.get("time", 0) or 0)
            for case in suite.iter("testcase"):
                for bad in list(case.findall("failure")) + list(case.findall("error")):
                    msg = (bad.get("message") or bad.text or "").strip().splitlines()
                    failures.append((f"{case.get('classname')}.{case.get('name')}", msg[0][:200] if msg else ""))
    passed = total - failed - errors - skipped
    icon = "✅" if total and not (failed or errors) else ("⚠️" if not total else "❌")
    print(f"### {icon} {title}\n")
    print("| Total | Passed | Failed | Errors | Skipped | Time |")
    print("|---:|---:|---:|---:|---:|---:|")
    print(f"| {total} | {passed} | {failed} | {errors} | {skipped} | {seconds:.0f}s |\n")
    if not total:
        print("No test results were found.\n")
    for name, msg in failures[:30]:
        print(f"- `{name}`: {msg}")
    if len(failures) > 30:
        print(f"- ...and {len(failures) - 30} more")


if __name__ == "__main__":
    main()
