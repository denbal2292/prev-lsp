import json

EDIT_KINDS = [
    ("RENAME_LOCAL", "rename local"),
    ("RENAME_GLOBAL", "rename global"),
    ("CHANGE_TYPE_NARROW", "named type, 1 use"),
    ("CHANGE_TYPE_WIDE", "named type, n uses"),
]
FUNCTIONS = ["250", "1000", "4000"]
PHASES = ["NAMES", "TYPES"]
ENGINES = ["INCREMENTAL", "FULL"]

# JMH reports us/op; the table is in ms.
scores = {}
for entry in json.load(open("semantic-update-jmh.json")):
    p = entry["params"]
    key = (p["editKind"], p["functions"], p["phase"], p["engine"])
    scores[key] = entry["primaryMetric"]["score"] / 1000


def num(value):
    """Two decimals while they still say something, none once they do not."""
    return f"{value:,.2f}" if value < 10 else f"{value:,.0f}"


print("Semantic update cost, ms\n")
print(f"{'':<20}{'n':>6}" + "".join(f"{g:^18}" for g in ["names", "types", "total"])
      + f"{'speedup':>9}")
print(f"{'':<26}" + "".join(f"{h:>9}" for h in ["inc", "full"] * 3))

for kind, label in EDIT_KINDS:
    for i, n in enumerate(FUNCTIONS):
        cells, totals = [], {}
        for engine in ENGINES:
            totals[engine] = sum(scores[(kind, n, ph, engine)] for ph in PHASES)
        for phase in PHASES:
            cells += [num(scores[(kind, n, phase, e)]) for e in ENGINES]
        cells += [num(totals[e]) for e in ENGINES]
        cells.append(f"{totals['FULL'] / totals['INCREMENTAL']:,.1f}x")
        print(f"{label if i == 0 else '':<20}{n:>6}" + "".join(f"{c:>9}" for c in cells))
    print()
