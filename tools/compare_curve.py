"""Compares no flattening, curved row coordinates, text-line remap and UVDoc on the same OCR.

Usage: .venv-paddle/Scripts/python tools/compare_curve.py real|flat|curl|crease
  real    hand-checked phone photos of curled receipts (private, listed in REAL_TOTALS)
  flat    labelled samples as they are, to catch damage on paper that needs no fixing
  curl, crease  the same samples with a synthetic bend of known size

OCR output, images and reports are cached under logs/curve-compare-v2/, so reruns only redo parsing.
"""
import json
import os
import sys
import time
import zlib
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parent.parent
sys.path[:0] = [str(ROOT / "app"), str(ROOT / "tools")]
sys.stdout.reconfigure(encoding="utf-8")
os.environ.setdefault("PADDLE_PDX_CACHE_HOME", str(ROOT / "models/paddlex"))
os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import curve_rows  # noqa: E402
import receipt  # noqa: E402

OUT = ROOT / "logs/curve-compare-v2"
METHODS = ("none", "rows", "warp", "gated", "uvdoc")
SKIPPED_PREFIXES = ("cn_", "kr_", "run_")
# Read off the receipts by hand; each receipt was photographed several times.
GROUNDS = (108.5, 5)
BUNNINGS = (42.84, 4)
REAL_TOTALS = {
    "015940": GROUNDS, "020022": GROUNDS, "020216": BUNNINGS, "020245": BUNNINGS,
    "020325": BUNNINGS, "021534": BUNNINGS, "021556": BUNNINGS, "022813": BUNNINGS,
    "022830": GROUNDS, "022905": GROUNDS,
}
pipelines = {}


def ocr(image_path):
    if "ocr" not in pipelines:
        from paddleocr import PaddleOCR

        pipelines["ocr"] = PaddleOCR(
            text_detection_model_name="PP-OCRv6_small_det",
            text_recognition_model_name="PP-OCRv6_small_rec",
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            device="cpu",
            cpu_threads=8,
            enable_mkldnn=False,
        )
    page = pipelines["ocr"].predict(str(image_path))[0]
    return [
        {"text": text, "score": float(score), "box": [int(v) for v in box],
         "poly": [[int(x), int(y)] for x, y in poly]}
        for text, score, box, poly in zip(
            page["rec_texts"], page["rec_scores"], page["rec_boxes"].tolist(), page["rec_polys"],
        )
        if text.strip()
    ]


def unwarp(image_path):
    if "uvdoc" not in pipelines:
        from paddleocr import TextImageUnwarping

        pipelines["uvdoc"] = TextImageUnwarping(model_name="UVDoc", device="cpu")
    picture = np.array(pipelines["uvdoc"].predict(str(image_path))[0]["doctr_img"])
    return cv2.cvtColor(picture, cv2.COLOR_RGB2BGR)


def cached_ocr(key, image_path):
    path = OUT / "ocr" / f"{key}.json"
    if path.exists():
        return json.loads(path.read_text("utf8"))
    start = time.perf_counter()
    lines = ocr(image_path)
    data = {"lines": lines, "ms": round((time.perf_counter() - start) * 1000)}
    path.write_text(json.dumps(data, ensure_ascii=False), "utf8")
    return data


def bent(image, lines, kind, name):
    """Applies a known bend scaled to the text height, seeded by file name."""
    random = np.random.default_rng(zlib.crc32(name.encode()))
    height, width = image.shape[:2]
    text = median(line["box"][3] - line["box"][1] for line in lines)
    x, y = np.meshgrid(np.arange(width, dtype=np.float32), np.arange(height, dtype=np.float32))
    phase = random.uniform(0, 2 * np.pi)
    shift = 2.5 * text * np.sin(2 * np.pi * x / (1.2 * width) + phase + 1.5 * y / height)
    squeeze = 0.012 * width * np.sin(2 * np.pi * x / width + phase)
    if kind == "crease":
        fold = width * random.uniform(0.35, 0.65) + 0.15 * width * (y / height - 0.5)
        shift = 0.4 * shift + random.choice((-1, 1)) * 0.12 * np.abs(x - fold)
    map_y = (y - (shift - shift.mean())).astype(np.float32)
    map_x = (x + squeeze).astype(np.float32)
    return cv2.remap(image, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def sources(stage):
    """Yields (key, source image path, flat key used for the bend or None, truth)."""
    if stage == "real":
        for stamp, truth in REAL_TOTALS.items():
            yield f"real_{stamp}", ROOT / f"samples/run_20261102-{stamp}.jpg", truth
        return
    labels = json.loads((ROOT / "samples/ground_truth.json").read_text("utf8"))
    for name in sorted(labels):
        if name.startswith(SKIPPED_PREFIXES) or not (ROOT / "samples" / name).exists():
            continue
        truth = receipt.from_cord(labels[name])
        stem = Path(name).stem
        if stage == "flat":
            yield f"flat_{stem}", ROOT / "samples" / name, truth
            continue
        path = OUT / "images" / f"{stage}_{stem}.jpg"
        if not path.exists():
            flat = cached_ocr(f"flat_{stem}", ROOT / "samples" / name)
            image = bent(cv2.imread(str(ROOT / "samples" / name)), flat["lines"], stage, name)
            cv2.imwrite(str(path), image, [cv2.IMWRITE_JPEG_QUALITY, 95])
        yield f"{stage}_{stem}", path, truth


def score(lines, truth):
    parsed = receipt.parse_lines(lines)
    result = {"total": parsed["total"], "items": len(parsed["items"]), "sum_ok": receipt.sum_check(parsed)}
    if isinstance(truth, tuple):
        result["total_ok"] = parsed["total"] is not None and abs(parsed["total"] - truth[0]) < 0.011
        result["items_ok"] = result["items"] == truth[1]
    else:
        scored = receipt.score(parsed, truth)
        result["total_ok"] = bool(scored["total_ok"])
        result["item_f1"] = scored.get("item_f1")
    return result


def process(key, path, truth):
    image = cv2.imread(str(path))
    height, width = image.shape[:2]
    base = cached_ocr(key, path)
    lines = base["lines"]
    row = {"key": key, "ocr_ms": base["ms"], "none": score(lines, truth)}

    start = time.perf_counter()
    field, bend = curve_rows.fit(lines, width, height)
    row["fit_ms"] = round((time.perf_counter() - start) * 1000, 1)
    row["bend"] = round(bend, 2)
    # Runs on every receipt so damage shows; "gated" is what a bend threshold would have done.
    usable = field is not None
    row["rows"] = score(curve_rows.straighten_lines(lines, field), truth) if usable else row["none"]

    fixed = None
    if usable:
        fixed_path = OUT / "images" / f"{key}_warp.jpg"
        if not fixed_path.exists():
            start = time.perf_counter()
            picture = curve_rows.flatten(image, field)
            row["warp_ms"] = round((time.perf_counter() - start) * 1000, 1)
            if picture is not None:
                cv2.imwrite(str(fixed_path), picture, [cv2.IMWRITE_JPEG_QUALITY, 95])
                cv2.imwrite(str(OUT / "images" / f"{key}_rows.jpg"), curve_rows.draw_rows(image, field))
        if fixed_path.exists():
            fixed = fixed_path
    row["warp"] = score(cached_ocr(f"{key}_warp", fixed)["lines"], truth) if fixed else row["none"]

    row["gated"] = row["warp"] if bend >= curve_rows.MIN_BEND_HEIGHTS else row["none"]

    uvdoc_path = OUT / "images" / f"{key}_uvdoc.jpg"
    if not uvdoc_path.exists():
        start = time.perf_counter()
        picture = unwarp(path)
        row["uvdoc_ms"] = round((time.perf_counter() - start) * 1000)
        cv2.imwrite(str(uvdoc_path), picture, [cv2.IMWRITE_JPEG_QUALITY, 95])
    row["uvdoc"] = score(cached_ocr(f"{key}_uvdoc", uvdoc_path)["lines"], truth)
    return row


def summarise(rows):
    print(f"\n{len(rows)} 张")
    for method in METHODS:
        parts = [method.ljust(6), f"总额 {sum(r[method]['total_ok'] for r in rows)}/{len(rows)}",
                 f"求和 {sum(bool(r[method]['sum_ok']) for r in rows)}/{len(rows)}"]
        if "items_ok" in rows[0]["none"]:
            parts.append(f"菜品数 {sum(r[method]['items_ok'] for r in rows)}/{len(rows)}")
        f1s = [r[method]["item_f1"] for r in rows if r[method].get("item_f1") is not None]
        if f1s:
            parts.append(f"F1 {sum(f1s) / len(f1s):.3f}（{len(f1s)} 张）")
        print("  ".join(parts))


def main():
    stage = sys.argv[1]
    (OUT / "ocr").mkdir(parents=True, exist_ok=True)
    (OUT / "images").mkdir(parents=True, exist_ok=True)
    report = OUT / f"{stage}.json"
    rows = []
    for key, path, truth in sources(stage):
        row = process(key, path, truth)
        rows.append(row)
        cells = "  ".join(
            f"{m} {row[m]['total']}/{row[m]['items']}{'+' if row[m]['sum_ok'] else '-'}" for m in METHODS
        )
        print(f"{key}  bend {row['bend']}  {cells}", flush=True)
        report.write_text(json.dumps(rows, ensure_ascii=False, indent=1), "utf8")
    summarise(rows)


if __name__ == "__main__":
    main()
