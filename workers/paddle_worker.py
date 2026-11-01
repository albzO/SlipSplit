"""PP-OCRv6 Tiny / Small / Medium, run through PaddleOCR on CPU."""
from common import CPU_THREADS, MODELS, Timer, dir_mb, serve

TIERS = {"ppocr_tiny": "tiny", "ppocr_small": "small", "ppocr_medium": "medium"}
pipelines = {}


def load(engine):
    from paddleocr import PaddleOCR

    tier = TIERS[engine]
    return PaddleOCR(
        text_detection_model_name=f"PP-OCRv6_{tier}_det",
        text_recognition_model_name=f"PP-OCRv6_{tier}_rec",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        device="cpu",
        cpu_threads=CPU_THREADS,
        # paddlepaddle 3.3 fails on PP-OCRv6 graphs with ConvertPirAttribute2RuntimeAttribute.
        enable_mkldnn=False,
    )


def to_lines(page):
    boxes = page["rec_boxes"]
    return [
        {"text": text, "score": round(float(score), 4), "box": [int(v) for v in box]}
        for text, score, box in zip(page["rec_texts"], page["rec_scores"], boxes.tolist())
    ]


def infer(msg):
    engine = msg["engine"]
    load_ms = 0
    if engine not in pipelines:
        with Timer() as t:
            pipelines[engine] = load(engine)
        load_ms = t.ms
    with Timer() as t:
        page = pipelines[engine].predict(msg["image"])[0]
    lines = to_lines(page)
    tier = TIERS[engine]
    root = MODELS / "paddlex" / "official_models"
    sizes = [dir_mb(root / f"PP-OCRv6_{tier}_{part}") for part in ("det", "rec")]
    return {
        "load_ms": load_ms,
        "infer_ms": t.ms,
        "lines": lines,
        "raw_text": "\n".join(line["text"] for line in lines),
        "model_mb": round(sum(s or 0 for s in sizes), 1),
    }


serve(infer)
