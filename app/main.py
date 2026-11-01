"""Receipt OCR comparison server."""
import json
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageOps
from pydantic import BaseModel

import engines
import receipt

ROOT = Path(__file__).resolve().parent.parent
SAMPLES = ROOT / "samples"
UPLOADS = ROOT / "uploads"
UPLOADS.mkdir(exist_ok=True)
MAX_SIDE = 2400


@asynccontextmanager
async def lifespan(_):
    yield
    engines.shutdown()


app = FastAPI(lifespan=lifespan)
app.mount("/samples", StaticFiles(directory=SAMPLES), name="samples")
app.mount("/uploads", StaticFiles(directory=UPLOADS), name="uploads")

truth_path = SAMPLES / "ground_truth.json"
TRUTH = json.loads(truth_path.read_text("utf-8")) if truth_path.exists() else {}
dependency_cache = {}


class InferRequest(BaseModel):
    image: str
    engine: str
    true_total: float | None = None


def image_info(path, url):
    with Image.open(path) as image:
        return {"id": url, "url": url, "name": path.name, "width": image.width, "height": image.height}


def resolve(image_id):
    folder, _, name = image_id.strip("/").partition("/")
    base = {"samples": SAMPLES, "uploads": UPLOADS}.get(folder)
    path = base / name if base else None
    if not path or path.name != name or not path.is_file():
        raise HTTPException(404, "image not found")
    return path


def structure(engine, output):
    parser = engines.ENGINES[engine]["parser"]
    if parser == "cord":
        return receipt.from_cord(output.get("native"))
    if parser == "entities":
        return receipt.from_entities(output["native"]["entities"])
    return receipt.parse_lines(output["lines"])


@app.get("/")
def index():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/api/config")
def config():
    samples = sorted(
        (p for p in SAMPLES.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}),
        key=lambda p: (len(p.name), p.name),
    )
    return {
        "engines": [{"id": key, **spec} for key, spec in engines.ENGINES.items()],
        "samples": [image_info(p, f"/samples/{p.name}") for p in samples],
    }


@app.post("/api/upload")
def upload(file: UploadFile):
    try:
        image = ImageOps.exif_transpose(Image.open(file.file)).convert("RGB")
    except OSError:
        raise HTTPException(400, "not an image")
    image.thumbnail((MAX_SIDE, MAX_SIDE))
    path = UPLOADS / f"{uuid.uuid4().hex[:12]}.jpg"
    image.save(path, quality=95)
    return image_info(path, f"/uploads/{path.name}")


@app.post("/api/infer")
def infer(request: InferRequest):
    if request.engine not in engines.ENGINES:
        raise HTTPException(404, "unknown engine")
    path = resolve(request.image)
    spec = engines.ENGINES[request.engine]
    upstream_ms = 0
    lines = None
    if "depends_on" in spec:
        key = (request.image, spec["depends_on"])
        if key not in dependency_cache:
            first = engines.run_engine(spec["depends_on"], path)
            if not first["ok"]:
                return first
            dependency_cache[key] = first
        lines = dependency_cache[key]["lines"]
        upstream_ms = dependency_cache[key]["infer_ms"]
    output = engines.run_engine(request.engine, path, lines)
    if not output["ok"]:
        return output
    output["infer_ms"] += upstream_ms
    output["upstream_ms"] = upstream_ms
    parsed = structure(request.engine, output)
    if parsed["total"] is None:
        parsed["total"] = parsed["subtotal"]
    output.pop("native", None)
    output["receipt"] = parsed
    output["sum_ok"] = receipt.sum_check(parsed)
    truth = TRUTH.get(path.name)
    if truth is not None:
        output["score"] = receipt.score(parsed, receipt.from_cord(truth))
    elif request.true_total is not None:
        output["score"] = {"total_ok": receipt.close(parsed["total"], request.true_total)}
    return output


app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")
