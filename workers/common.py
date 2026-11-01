"""JSONL request loop shared by the model workers.

Runs inside each worker's own venv, so it must stay standard-library only.
OCR libraries print freely to stdout, so fd 1 is pointed at stderr and the
protocol uses a private duplicate of the original stdout.
"""
import json
import os
import sys
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
CPU_THREADS = 8

os.environ.setdefault("HF_HOME", str(MODELS / "hf"))
os.environ.setdefault("PADDLE_PDX_CACHE_HOME", str(MODELS / "paddlex"))
os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")


def dir_mb(path):
    path = Path(path)
    if not path.exists():
        return None
    return round(sum(f.stat().st_size for f in path.rglob("*") if f.is_file()) / 1e6, 1)


def largest_file_mb(path):
    """Weights size; Hugging Face may cache .bin and .safetensors copies side by side."""
    path = Path(path)
    if not path.exists():
        return None
    return round(max(f.stat().st_size for f in path.rglob("*") if f.is_file()) / 1e6, 1)


def serve(infer):
    """Answer one request per line; `infer(msg)` returns the result dict."""
    out = os.fdopen(os.dup(1), "w", encoding="utf-8", buffering=1)
    os.dup2(2, 1)
    sys.stdin.reconfigure(encoding="utf-8")
    out.write(json.dumps({"ready": True}) + "\n")
    for line in sys.stdin:
        msg = json.loads(line)
        try:
            result = infer(msg)
            result["ok"] = True
        except Exception as exc:
            traceback.print_exc()
            result = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        out.write(json.dumps(result, ensure_ascii=False) + "\n")


class Timer:
    def __enter__(self):
        self.start = time.perf_counter()
        return self

    def __exit__(self, *_):
        self.ms = round((time.perf_counter() - self.start) * 1000)
