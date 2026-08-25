#!/usr/bin/env python3
"""Render positions.pdf from the JMH CSV produced by ./gradlew jmh."""

import csv
from collections import defaultdict
from pathlib import Path
from statistics import mean

import matplotlib.pyplot as plt

HERE = Path(__file__).parent
BACKENDS = ["prevFull", "baseline", "array", "treap"]
POSITIONS = ["FRONT", "MIDDLE", "END"]

LABEL = {
    "baseline": "nesploščeno",
    "array": "dinamična tabela",
    "treap": "naključno uravnoteženo drevo",
    "prevFull": "ponovna analiza celotnega dokumenta",
}
STYLE = {
    "baseline": {"color": "#B45309", "marker": "s"},
    "array": {"color": "#1D4ED8", "marker": "o"},
    "treap": {"color": "#047857", "marker": "^"},
    "prevFull": {"color": "#6B7280", "marker": "v", "linestyle": "--"},
}
POSITION_LABEL = {
    "FRONT": "sprememba na začetku",
    "MIDDLE": "sprememba na sredini",
    "END": "sprememba na koncu",
}

plt.rcParams.update({
    "font.size": 7,
    "axes.labelsize": 7,
    "axes.titlesize": 7.5,
    "xtick.labelsize": 6,
    "ytick.labelsize": 6,
    "axes.linewidth": 0.6,
    "xtick.major.width": 0.6,
    "ytick.major.width": 0.6,
    "savefig.transparent": False,
})


def read_scores():
    scores = defaultdict(list)
    with (HERE / "positions.csv").open() as rows:
        for row in csv.DictReader(rows):
            key = (
                row["Param: backend"],
                int(row["Param: definitions"]),
                row["Param: position"],
            )
            scores[key].append(float(row["Score"]))
    return {key: mean(values) for key, values in scores.items()}


def main():
    scores = read_scores()
    sizes = sorted({definitions for _, definitions, _ in scores})
    figure, axes = plt.subplots(1, 3, figsize=(6.9, 2.3), sharey=True)

    for axis, position in zip(axes, POSITIONS):
        for backend in BACKENDS:
            values = [scores[(backend, size, position)] for size in sizes]
            axis.plot(
                sizes,
                values,
                label=LABEL[backend],
                markersize=4,
                linewidth=1.4,
                **STYLE[backend],
            )
        axis.set(
            xscale="log",
            yscale="log",
            xlabel="število definicij",
            title=POSITION_LABEL[position],
        )
        axis.grid(True, which="major", linewidth=0.3, alpha=0.6)

    axes[0].set_ylabel("čas obdelave spremembe [µs]")
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(
        handles,
        labels,
        fontsize=6,
        frameon=False,
        loc="lower center",
        ncol=4,
        bbox_to_anchor=(0.5, -0.07),
    )
    figure.tight_layout()

    output = HERE / "positions.pdf"
    figure.savefig(output, bbox_inches="tight")
    print(f"wrote {output}")


if __name__ == "__main__":
    main()
