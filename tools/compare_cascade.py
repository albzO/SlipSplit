"""Tests UVDoc followed by text-line levelling against UVDoc alone.

Usage: .venv-paddle/Scripts/python tools/compare_cascade.py
Reuses UVDoc images from tools/compare_curve.py; phone photos saved with flatten_backend "uvdoc"
are already UVDoc output. Run compare_curve.py first.
"""
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import compare_curve as bench  # noqa: E402
import curve_rows  # noqa: E402
import receipt  # noqa: E402

PHONE_TRUTHS = {
    "053351-487": (108.5, 5),
    "181449-249": (19.5, 2),
    "181909-977": (9.5, 2),
    # Delivery order: fees push the charge to 38.80, but the split is over the 37.24 of food.
    "182214-317": (37.24, 3),
}


def tilt(lines):
    """Median and 90th percentile of how far text rows sit from level, in degrees."""
    samples, _ = curve_rows.slope_samples(lines)
    if not len(samples):
        return None
    degrees = np.degrees(np.arctan(samples[:, 2]))
    return {"median": round(float(np.median(np.abs(degrees))), 2),
            "p90": round(float(np.quantile(np.abs(degrees), 0.9)), 2)}


def cases():
    for stamp, truth in PHONE_TRUTHS.items():
        yield f"phone_{stamp}", ROOT / f"samples/run_20261102-{stamp}.jpg", truth
    for stage in ("real", "flat", "curl", "crease"):
        for key, _, truth in bench.sources(stage):
            yield key, bench.OUT / "images" / f"{key}_uvdoc.jpg", truth


def run(key, path, truth):
    image = cv2.imread(str(path))
    base = bench.cached_ocr(f"{key}_uvdoc", path)
    row = {"key": key, "uvdoc": bench.score(base["lines"], truth), "tilt_before": tilt(base["lines"])}
    height, width = image.shape[:2]
    start = time.perf_counter()
    field, bend = curve_rows.fit(base["lines"], width, height)
    level = curve_rows.flatten(image, field) if field else None
    row["level_ms"] = round((time.perf_counter() - start) * 1000, 1)
    row["bend"] = round(bend, 2)
    if level is None:
        row["cascade"] = row["uvdoc"]
        row["tilt_after"] = row["tilt_before"]
        return row
    fixed = bench.OUT / "images" / f"{key}_cascade.jpg"
    cv2.imwrite(str(fixed), level, [cv2.IMWRITE_JPEG_QUALITY, 95])
    lines = bench.cached_ocr(f"{key}_cascade", fixed)["lines"]
    row["cascade"] = bench.score(lines, truth)
    row["tilt_after"] = tilt(lines)
    parsed = receipt.parse_lines(lines)
    row["rows"] = [text for text, _, _ in receipt.group_rows(receipt.deskew(lines))]
    row["items"] = [item["name"] for item in parsed["items"]]
    return row


def summary(name, rows):
    def share(method, field):
        return sum(bool(r[method].get(field)) for r in rows)

    f1s = {m: [r[m]["item_f1"] for r in rows if r[m].get("item_f1") is not None] for m in ("uvdoc", "cascade")}
    tilted = [r for r in rows if r["tilt_before"] and r["tilt_after"]]
    print(f"\n{name}：{len(rows)} 张")
    for method in ("uvdoc", "cascade"):
        line = f"{method}  总额 {share(method, 'total_ok')}  求和 {share(method, 'sum_ok')}"
        if f1s[method]:
            line += f"  F1 {sum(f1s[method]) / len(f1s[method]):.3f}"
        print(line)
    if tilted:
        before = np.median([r["tilt_before"]["median"] for r in tilted])
        after = np.median([r["tilt_after"]["median"] for r in tilted])
        worst_before = np.median([r["tilt_before"]["p90"] for r in tilted])
        worst_after = np.median([r["tilt_after"]["p90"] for r in tilted])
        print(f"行倾斜 中位 {before:.2f}° -> {after:.2f}°，p90 {worst_before:.2f}° -> {worst_after:.2f}°")


def main():
    rows = []
    for key, path, truth in cases():
        row = run(key, path, truth)
        rows.append(row)
        cells = "  ".join(
            f"{m} {row[m]['total']}/{row[m]['items']}{'+' if row[m]['sum_ok'] else '-'}" for m in ("uvdoc", "cascade")
        )
        print(f"{key}  bend {row['bend']}  {row['tilt_before']} -> {row['tilt_after']}  {cells}", flush=True)
        (bench.OUT / "cascade.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), "utf8")
    for name in ("phone", "real", "flat", "curl", "crease"):
        summary(name, [r for r in rows if r["key"].startswith(name + "_")])
    print("level ms median", np.median([r["level_ms"] for r in rows]))


if __name__ == "__main__":
    main()
