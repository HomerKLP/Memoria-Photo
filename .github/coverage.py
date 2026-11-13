"""Line coverage of the unit and device tests together, as Markdown for the GitHub run page."""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def lines(path):
    seen, hit = set(), set()
    for pkg in ET.parse(path).getroot().iter("package"):
        for src in pkg.iter("sourcefile"):
            name = f"{pkg.get('name')}/{src.get('name')}"
            for line in src.iter("line"):
                seen.add((name, line.get("nr")))
                if int(line.get("ci")) > 0:
                    hit.add((name, line.get("nr")))
    return seen, hit


def pct(hit, seen):
    return f"{100 * len(hit) / len(seen):.0f}%" if seen else "-"


# The device tests come in several parts (one report each), so reports are grouped by kind.
kinds = defaultdict(lambda: (set(), set()))
for path in sys.argv[1:]:
    s, h = lines(path)
    kind = kinds["Device tests" if "androidTest" in path else "Unit tests"]
    kind[0].update(s)
    kind[1].update(h)
if not kinds:
    print("## Test coverage\nNo report: the tests did not get far enough.")
    sys.exit()
seen = set().union(*(s for s, _ in kinds.values()))
hit = set().union(*(h for _, h in kinds.values()))

print(f"## Test coverage: {pct(hit, seen)} of lines")
for kind, (s, h) in sorted(kinds.items()):
    print(f"- {kind} alone: {pct(h, s)}")

files = defaultdict(lambda: [0, 0])
for name, _ in seen:
    files[name][1] += 1
for name, _ in hit:
    files[name][0] += 1
print("\n| File | Lines | Covered |\n|---|---:|---:|")
for name, (h, s) in sorted(files.items(), key=lambda f: f[1][0] / f[1][1]):
    print(f"| {name} | {s} | {100 * h / s:.0f}% |")
