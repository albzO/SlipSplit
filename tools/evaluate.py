"""Scores post-processing on saved OCR output, in seconds and without running a model.

Usage: python tools/evaluate.py [source] [--save] [--fixture] [--all]
  source     folder under ocr_cache, default ppocr_small
  --all      print every receipt, not only the ones with problems
  --save     keep this run as the baseline the next run is compared with
  --fixture  refresh the Kotlin parser test from this source
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "app"))
sys.stdout.reconfigure(encoding="utf-8")

import receipt  # noqa: E402

CACHE = ROOT / "ocr_cache"
FIXTURE = ROOT / "android-bridge/app/src/test/resources/receipts.json"
# Chinese receipts are out of focus for now; they stay in the Kotlin fixture.
SKIPPED_PREFIXES = ("cn_",)


def load_truth():
    path = ROOT / "samples/ground_truth.json"
    return json.loads(path.read_text("utf8"))


def item_text(item):
    spec = f' [{item["spec"]}]' if item.get("spec") else ""
    return f'{item["name"]}{spec} × {item["qty"]} = {item["price"]}'


def evaluate(path, truth):
    data = json.loads(path.read_text("utf8"))
    lines = [line for line in data["lines"] if line["text"].strip()]
    parsed = receipt.parse_lines(lines)
    result = {
        "image": data["image"],
        "lines": [{key: line[key] for key in ("text", "box", "poly") if key in line} for line in lines],
        "parsed": parsed,
        "sum_ok": receipt.sum_check(parsed),
    }
    expected = truth.get(data["image"])
    if expected is None:
        return result
    labelled = receipt.from_cord(expected)
    result["score"] = receipt.score(parsed, labelled)
    _, missed, extra = receipt.match_items(parsed, labelled)
    if labelled["items"]:
        result["missed"] = [item_text(item) for item in missed]
        result["extra"] = [item_text(item) for item in extra]
    result["true_total"] = labelled["total"]
    return result


def fingerprint(result):
    score = result.get("score", {})
    return {
        "total": result["parsed"]["total"],
        "total_ok": score.get("total_ok"),
        "item_f1": score.get("item_f1"),
        "sum_ok": result["sum_ok"],
    }


def has_problem(result):
    score = result.get("score", {})
    return result["parsed"]["dropped"] or score.get("total_ok") is False or not result["sum_ok"] or (
        score.get("item_f1") is not None and score["item_f1"] < 1
    )


def describe(result):
    parsed = result["parsed"]
    score = result.get("score", {})
    flags = [f'总额 {parsed["total"]}']
    if score.get("total_ok") is False:
        flags[0] += f'（应为 {result["true_total"]}）'
    if score.get("item_f1") is not None:
        flags.append(f'F1 {score["item_f1"]}')
    flags.append("求和对" if result["sum_ok"] else "求和错")
    print(f'\n{result["image"]}  ' + "  ".join(flags))
    print(f'  店名 {parsed["company"] or "—"}  时间 {parsed["date"] or "—"} {parsed["time"] or "—"}')
    for item in result.get("missed", []):
        print(f"  漏 {item}")
    for item in result.get("extra", []):
        print(f"  多 {item}")
    if "missed" not in result:
        for item in parsed["items"]:
            print(f"  读到 {item_text(item)}")
    for item in parsed["dropped"]:
        print(f"  剔除 {item_text(item)}")


def main():
    args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    source = args[0] if args else "ppocr_small"
    folder = CACHE / source
    paths = sorted(folder.glob("*.json"))
    if not paths:
        sys.exit(f"nothing saved in {folder}; run tools/dump_ocr.py first")
    truth = load_truth()
    results = [evaluate(path, truth) for path in paths]

    focus = [r for r in results if not r["image"].startswith(SKIPPED_PREFIXES)]
    for result in focus:
        if "--all" in sys.argv or has_problem(result):
            describe(result)

    scored = [r for r in focus if r.get("score", {}).get("total_ok") is not None]
    f1s = [r["score"]["item_f1"] for r in scored if r["score"].get("item_f1") is not None]
    print(f"\n{source}: {len(focus)} 张")
    print(f'总额正确 {sum(bool(r["score"]["total_ok"]) for r in scored)}/{len(scored)}')
    if f1s:
        print(f"菜品 F1 {sum(f1s) / len(f1s):.3f}（{len(f1s)} 张）")
    print(f'求和对得上 {sum(r["sum_ok"] for r in focus)}/{len(focus)}')

    baseline_path = CACHE / f"baseline_{source}.json"
    current = {r["image"]: fingerprint(r) for r in focus}
    if baseline_path.exists():
        baseline = json.loads(baseline_path.read_text("utf8"))
        changed = [name for name, value in current.items() if baseline.get(name) != value]
        print(f"\n与基线相比变化 {len(changed)} 张")
        for name in changed:
            print(f"  {name}: {baseline.get(name)} -> {current[name]}")
    if "--save" in sys.argv:
        baseline_path.write_text(json.dumps(current, ensure_ascii=False, indent=1), "utf8")
        print(f"基线已保存到 {baseline_path.name}")
    if "--fixture" in sys.argv:
        fixture = [
            {"image": r["image"], "lines": r["lines"], "expected": r["parsed"], "sum_ok": r["sum_ok"]}
            for r in results
        ]
        FIXTURE.write_text(json.dumps(fixture, ensure_ascii=False), "utf8")
        print(f"Kotlin 对照数据已更新，共 {len(fixture)} 张")


if __name__ == "__main__":
    main()
