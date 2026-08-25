import csv
from collections import defaultdict
from statistics import mean

BACKENDS = ["baseline", "array", "treap", "prevFull"]
HEADINGS = ["unflattened", "dyn. array", "treap", "full reparse"]

POSITIONS = ["FRONT", "MIDDLE", "END"]
DEFINITIONS = [128, 512, 2048, 8192, 32768]

EDIT_KINDS = [("VALID", "operand"), ("ERROR", "operand -> end")]
TERMS = [1024, 4096, 16384]


def read(path, benchmark, *params):
    """Mean score per (backend, *params), keyed as written in the CSV."""
    scores = defaultdict(list)
    for row in csv.DictReader(open(path)):
        if row["Benchmark"].endswith(benchmark):
            key = tuple(row[f"Param: {p}"] for p in params)
            scores[(row["Param: backend"],) + key].append(float(row["Score"]))
    return {k: mean(v) for k, v in scores.items()}


def row(label, width, cells):
    print(f"{label:<{width}}" + "".join(f"{c:>14}" for c in cells))


def positions():
    scores = read("positions.csv", "edits", "position", "definitions")

    print("Edit cost by document size and edit position, us/op\n")
    row("", 12, HEADINGS)
    for position in POSITIONS:
        print(f"{position.lower()}")
        for size in DEFINITIONS:
            cells = [f"{scores[(b, position, str(size))]:,.1f}" for b in BACKENDS]
            row(f"{size:>10}  ", 12, cells)


def tail():
    scores = read("tail.csv", "edit", "editKind", "terms")

    print("\nEdit at the head of an expression with a flattened tail, us/op\n")
    row("", 18, HEADINGS)
    for kind, label in EDIT_KINDS:
        print(label)
        for terms in TERMS:
            cells = [f"{scores[(b, kind, str(terms))]:,.1f}" for b in BACKENDS]
            row(f"{terms:>16}  ", 18, cells)


positions()
tail()
