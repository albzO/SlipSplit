"""Copies the App's saved runs from a device into samples/ and ocr_cache/app/.

Usage: python tools/pull_runs.py <adb serial>
Each run becomes a sample named run_<time>.jpg with its OCR output, so tools/evaluate.py app
scores it. Add its true total and items to samples/ground_truth.json to label it.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
REMOTE = "/sdcard/Android/data/local.ocrbridge/files/runs"

if len(sys.argv) != 2:
    sys.exit(__doc__)
serial = sys.argv[1]
target = ROOT / "ocr_cache/app"
target.mkdir(parents=True, exist_ok=True)

with tempfile.TemporaryDirectory() as temp:
    subprocess.run(
        [str(ADB), "-s", serial, "pull", REMOTE, temp],
        check=True, creationflags=subprocess.CREATE_NO_WINDOW, capture_output=True,
    )
    added = 0
    for run in sorted((Path(temp) / "runs").iterdir()):
        name = f"run_{run.name}"
        destination = target / f"{name}.json"
        if destination.exists():
            continue
        shutil.copy(run / "photo.jpg", ROOT / "samples" / f"{name}.jpg")
        data = json.loads((run / "ocr.json").read_text("utf8"))
        data["image"] = f"{name}.jpg"
        destination.write_text(json.dumps(data, ensure_ascii=False, indent=1), "utf8")
        added += 1
print(f"added {added} runs; {len(list(target.glob('*.json')))} in {target}")
