"""Saves raw OCR output for every sample, so post-processing can be tested without OCR.

Usage: python tools/dump_ocr.py [engine] [--force]
Needs the server on port 8700. Samples already saved are skipped unless --force is given.
"""
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://localhost:8700"

args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
engine = args[0] if args else "ppocr_small"
force = "--force" in sys.argv
folder = ROOT / "ocr_cache" / engine
folder.mkdir(parents=True, exist_ok=True)

config = json.load(urllib.request.urlopen(BASE + "/api/config"))
saved = 0
for image in config["samples"]:
    target = folder / (Path(image["name"]).stem + ".json")
    if target.exists() and not force:
        continue
    request = urllib.request.Request(
        BASE + "/api/infer",
        json.dumps({"image": image["id"], "engine": engine}).encode(),
        {"Content-Type": "application/json"},
    )
    result = json.load(urllib.request.urlopen(request, timeout=900))
    if not result["ok"]:
        print(image["name"], "failed:", result["error"])
        continue
    payload = {"image": image["name"], "engine": engine, "lines": result["lines"]}
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=1), "utf8")
    saved += 1
print(f"saved {saved} files to {folder}")
