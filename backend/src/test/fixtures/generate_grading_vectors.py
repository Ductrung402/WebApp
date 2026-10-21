"""Deterministic Python reference for Java grading. No dependencies, credentials or database."""
from pathlib import Path

destination = Path(__file__).resolve().parents[1] / "resources" / "grading" / "python-round.csv"
destination.parent.mkdir(parents=True, exist_ok=True)
rows = [(c, t) for t in range(1, 41) for c in range(t + 1)]
rows += [(c, t) for t in (80, 160, 320, 500, 1000, 10000, 2147483647)
         for c in sorted({0, 1, t // 7, t // 3, t // 2, t - 1, t})]
destination.write_text("correct,total,pythonRound\n" + "".join(
    f"{c},{t},{round((c / t) * 10, 2)}\n" for c, t in rows), encoding="utf-8")
print(f"Wrote {len(rows)} Python reference vectors.")
