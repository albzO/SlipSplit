"""Donut-CORD, LayoutLMv3 KIE and PaddleOCR-VL 1.6, run through transformers on CPU."""
import re

from common import CPU_THREADS, MODELS, Timer, largest_file_mb, serve

import torch
from PIL import Image

DONUT_ID = "naver-clova-ix/donut-base-finetuned-cord-v2"
LAYOUTLM_ID = "mp-02/layoutlmv3-finetuned-cord"
VL_ID = "PaddlePaddle/PaddleOCR-VL-1.6"
VL_MAX_PIXELS = 2048 * 28 * 28
VL_MAX_NEW_TOKENS = 1536
VL_UPSCALE_BELOW = 1500
VL_LOC = re.compile(r"<\|LOC_(\d+)\|>")

torch.set_num_threads(CPU_THREADS)
loaded = {}


def load_donut():
    from transformers import DonutProcessor, VisionEncoderDecoderModel

    processor = DonutProcessor.from_pretrained(DONUT_ID)
    model = VisionEncoderDecoderModel.from_pretrained(DONUT_ID).eval()
    return processor, model


def load_layoutlm():
    from transformers import AutoProcessor, LayoutLMv3ForTokenClassification

    processor = AutoProcessor.from_pretrained(LAYOUTLM_ID, apply_ocr=False)
    model = LayoutLMv3ForTokenClassification.from_pretrained(LAYOUTLM_ID).eval()
    return processor, model


def load_vl():
    from transformers import AutoModelForImageTextToText, AutoProcessor

    processor = AutoProcessor.from_pretrained(VL_ID)
    model = AutoModelForImageTextToText.from_pretrained(VL_ID, torch_dtype=torch.bfloat16).eval()
    return processor, model


@torch.inference_mode()
def run_donut(msg):
    processor, model = loaded["donut"]
    image = Image.open(msg["image"]).convert("RGB")
    tok = processor.tokenizer
    pixel_values = processor(image, return_tensors="pt").pixel_values
    prompt = tok("<s_cord-v2>", add_special_tokens=False, return_tensors="pt").input_ids
    output = model.generate(
        pixel_values,
        decoder_input_ids=prompt,
        max_length=model.decoder.config.max_position_embeddings,
        pad_token_id=tok.pad_token_id,
        eos_token_id=tok.eos_token_id,
        use_cache=True,
        bad_words_ids=[[tok.unk_token_id]],
    )
    sequence = tok.batch_decode(output)[0]
    sequence = sequence.replace(tok.eos_token, "").replace(tok.pad_token, "")
    sequence = re.sub(r"<.*?>", "", sequence, count=1).strip()
    return {"raw_text": sequence, "lines": [], "native": processor.token2json(sequence)}


def split_words(lines):
    """Share each OCR line's box across its words, proportional to their length."""
    words, boxes = [], []
    for line in lines:
        x0, y0, x1, y1 = line["box"]
        parts = line["text"].split()
        total = sum(len(p) for p in parts) or 1
        cursor = x0
        for part in parts:
            width = (x1 - x0) * len(part) / total
            words.append(part)
            boxes.append([cursor, y0, cursor + width, y1])
            cursor += width
    return words, boxes


@torch.inference_mode()
def run_layoutlm(msg):
    processor, model = loaded["layoutlm"]
    image = Image.open(msg["image"]).convert("RGB")
    width, height = image.size
    words, boxes = split_words(msg["lines"])
    if not words:
        return {"raw_text": "", "lines": msg["lines"], "native": {"entities": []}}
    scaled = [
        [
            min(1000, max(0, int(1000 * x0 / width))),
            min(1000, max(0, int(1000 * y0 / height))),
            min(1000, max(0, int(1000 * x1 / width))),
            min(1000, max(0, int(1000 * y1 / height))),
        ]
        for x0, y0, x1, y1 in boxes
    ]
    encoded = processor(
        image, words, boxes=scaled, return_tensors="pt", truncation=True, max_length=512,
    )
    word_ids = encoded.word_ids(0)
    predicted = model(**encoded).logits[0].argmax(-1).tolist()
    labels = {}
    for index, word_id in enumerate(word_ids):
        if word_id is not None and word_id not in labels:
            labels[word_id] = model.config.id2label[predicted[index]]
    entities = []
    for word_id in sorted(labels):
        label = labels[word_id]
        if label == "O":
            continue
        tag, field = label.split("-", 1)
        if tag == "I" and entities and entities[-1]["field"] == field:
            entities[-1]["text"] += " " + words[word_id]
        else:
            entities.append({"field": field, "text": words[word_id]})
    return {
        "raw_text": "\n".join(f'{e["field"]}: {e["text"]}' for e in entities),
        "lines": msg["lines"],
        "native": {"entities": entities},
    }


def parse_spotting(text, width, height):
    """Spotting output is text followed by polygon points on a 0-1000 grid."""
    lines = []
    cursor = 0
    while True:
        first = VL_LOC.search(text, cursor)
        if not first:
            return lines
        points = []
        end = first.start()
        for match in VL_LOC.finditer(text, first.start()):
            if match.start() != end:
                break
            points.append(int(match.group(1)))
            end = match.end()
        label = text[cursor:first.start()].strip()
        cursor = end
        if label and len(points) >= 4:
            xs, ys = points[0::2], points[1::2]
            lines.append({"text": label, "score": None, "box": [
                int(min(xs) * width / 1000), int(min(ys) * height / 1000),
                int(max(xs) * width / 1000), int(max(ys) * height / 1000),
            ]})


@torch.inference_mode()
def run_vl(msg):
    processor, model = loaded["vl"]
    image = Image.open(msg["image"]).convert("RGB")
    width, height = image.size
    if width < VL_UPSCALE_BELOW and height < VL_UPSCALE_BELOW:
        image = image.resize((width * 2, height * 2), Image.Resampling.LANCZOS)
    messages = [{"role": "user", "content": [
        {"type": "image", "image": image},
        {"type": "text", "text": "Spotting:"},
    ]}]
    inputs = processor.apply_chat_template(
        messages,
        add_generation_prompt=True,
        tokenize=True,
        return_dict=True,
        return_tensors="pt",
        images_kwargs={"size": {
            "shortest_edge": processor.image_processor.size.shortest_edge,
            "longest_edge": VL_MAX_PIXELS,
        }},
    ).to(model.device)
    output = model.generate(**inputs, max_new_tokens=VL_MAX_NEW_TOKENS)
    text = processor.decode(output[0][inputs["input_ids"].shape[-1]:-1])
    lines = parse_spotting(text, width, height)
    return {"raw_text": "\n".join(line["text"] for line in lines) or text, "lines": lines}


ENGINES = {
    "donut": (load_donut, run_donut, DONUT_ID),
    "layoutlm": (load_layoutlm, run_layoutlm, LAYOUTLM_ID),
    "vl": (load_vl, run_vl, VL_ID),
}


def infer(msg):
    engine = msg["engine"]
    loader, runner, repo = ENGINES[engine]
    load_ms = 0
    if engine not in loaded:
        with Timer() as t:
            loaded[engine] = loader()
        load_ms = t.ms
    with Timer() as t:
        result = runner(msg)
    folder = "models--" + repo.replace("/", "--")
    result.update(
        load_ms=load_ms,
        infer_ms=t.ms,
        model_mb=largest_file_mb(MODELS / "hf" / "hub" / folder / "blobs"),
    )
    return result


serve(infer)
