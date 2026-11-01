"""Engine registry and the worker processes behind it."""
import json
import subprocess
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOGS = ROOT / "logs"
INFER_TIMEOUT_S = 900

ENGINES = {
    "ppocr_tiny": {
        "name": "PP-OCRv6 Tiny", "kind": "OCR", "worker": "paddle", "parser": "rules",
        "mobile": "适合手机：体积最小，可转 Paddle Lite / ONNX",
    },
    "ppocr_small": {
        "name": "PP-OCRv6 Small", "kind": "OCR", "worker": "paddle", "parser": "rules",
        "mobile": "适合手机：体积和准确率较均衡",
    },
    "ppocr_medium": {
        "name": "PP-OCRv6 Medium", "kind": "OCR", "worker": "paddle", "parser": "rules",
        "mobile": "较重：模型约 138 MB，需实测手机速度和内存",
    },
    "layoutlm": {
        "name": "PP-OCR + LayoutLMv3", "kind": "OCR + KIE", "worker": "torch",
        "parser": "entities", "depends_on": "ppocr_small",
        "mobile": "较重：需要手机端 ONNX 量化，CORD 社区权重",
    },
    "donut": {
        "name": "Donut-CORD", "kind": "端到端", "worker": "torch", "parser": "cord",
        "mobile": "较重：模型约 800 MB，手机上不实际",
    },
    "vl": {
        "name": "PaddleOCR-VL 1.6", "kind": "视觉大模型", "worker": "torch", "parser": "rules",
        "mobile": "不适合手机离线：CPU 单张约 13 分钟，只能放 GPU 服务器",
    },
}

WORKERS = {
    "paddle": (ROOT / ".venv-paddle/Scripts/python.exe", ROOT / "workers/paddle_worker.py"),
    "torch": (ROOT / ".venv-torch/Scripts/python.exe", ROOT / "workers/torch_worker.py"),
}


class Worker:
    """One long-lived process per venv, serving a single request at a time."""

    def __init__(self, name):
        self.name = name
        self.proc = None
        self.lock = threading.Lock()

    def start(self):
        python, script = WORKERS[self.name]
        LOGS.mkdir(exist_ok=True)
        log = open(LOGS / f"{self.name}.log", "a", encoding="utf-8")
        self.proc = subprocess.Popen(
            [str(python), "-u", str(script)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
            cwd=script.parent, encoding="utf-8", bufsize=1,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        if '"ready"' not in self.proc.stdout.readline():
            self.stop()
            raise RuntimeError(f"{self.name} worker failed to start, see logs/{self.name}.log")

    def stop(self):
        if self.proc:
            self.proc.kill()
            self.proc = None

    def call(self, message):
        with self.lock:
            if not self.proc or self.proc.poll() is not None:
                self.start()
            self.proc.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
            self.proc.stdin.flush()
            timer = threading.Timer(INFER_TIMEOUT_S, self.stop)
            timer.start()
            try:
                line = self.proc.stdout.readline()
            finally:
                timer.cancel()
            if not line:
                self.proc = None
                return {"ok": False, "error": f"{self.name} worker exited or timed out"}
            return json.loads(line)


workers = {name: Worker(name) for name in WORKERS}


def run_engine(engine, image_path, lines=None):
    spec = ENGINES[engine]
    message = {"engine": engine, "image": str(image_path)}
    if lines is not None:
        message["lines"] = lines
    return workers[spec["worker"]].call(message)


def shutdown():
    for worker in workers.values():
        worker.stop()
