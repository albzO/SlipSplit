"""Builds a local page showing image corrections and how rows became item blocks.

Usage: .venv-paddle/Scripts/python tools/block_view.py
Writes logs/block-view/index.html, which holds private receipts and stays on this machine.
Needs originals pulled to logs/block-view/originals/ and tools/compare_cascade.py run first.
"""
import base64
import html
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path[:0] = [str(ROOT / "app"), str(ROOT / "tools")]
sys.stdout.reconfigure(encoding="utf-8")

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import debug_document_edges as edges  # noqa: E402
import receipt  # noqa: E402

OUT = ROOT / "logs/block-view"
COMPARE = ROOT / "logs/curve-compare-v2"
RUNS = ("181449-249", "181909-977", "182214-317", "053351-487")
EDGE_SIDE = 1000
SHOWN_SIDE = 1100
HUES = (210, 25, 145, 330, 265, 50, 185, 0)
ROLES = {"name": "菜名", "amount": "金额行", "both": "菜名＋金额", "detail": "规格 / 修饰"}
ROLE_FILL = {"name": 0.42, "amount": 0.14, "both": 0.34, "detail": 0.14}


def data_uri(image):
    scale = min(1, SHOWN_SIDE / max(image.shape[:2]))
    small = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    encoded = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 88])[1]
    return "data:image/jpeg;base64," + base64.b64encode(encoded).decode()


def trapezoid(original):
    """Redoes the corner step with the desktop replay of the app's detector; the phone keeps no corners."""
    scale = EDGE_SIDE / max(original.shape[:2])
    found = edges.detect(cv2.resize(original, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA))
    if not found:
        return None
    points = (found[0][1] / scale).astype(np.float32)
    width = int(max(np.linalg.norm(points[1] - points[0]), np.linalg.norm(points[2] - points[3])))
    height = int(max(np.linalg.norm(points[3] - points[0]), np.linalg.norm(points[2] - points[1])))
    target = np.float32([[0, 0], [width, 0], [width, height], [0, height]])
    return cv2.warpPerspective(original, cv2.getPerspectiveTransform(points, target), (width, height))


def panel(title, note, image, overlay=""):
    height, width = image.shape[:2]
    svg = (f'<svg viewBox="0 0 {width} {height}" aria-hidden="true">{overlay}</svg>' if overlay else "")
    return (f'<figure><figcaption><b>{html.escape(title)}</b><span>{html.escape(note)}</span></figcaption>'
            f'<div class="stage"><img src="{data_uri(image)}" alt="{html.escape(title)}">{svg}</div></figure>')


def analyse(lines):
    trace = []
    parsed = receipt.parse_lines(lines, trace)
    return parsed, trace


def overlay_for(lines, trace):
    claimed = {}
    for entry in trace:
        if entry and "role" in entry:
            for cell in entry["cells"]:
                claimed[id(cell)] = entry
    # deskew copies the lines, so cells are matched back by their text and corners.
    by_shape = {(cell["text"], json.dumps(cell.get("poly"))): entry
                for entry in trace if entry and "role" in entry for cell in entry["cells"]}
    shapes = []
    for line in lines:
        entry = by_shape.get((line["text"], json.dumps(line.get("poly"))))
        points = " ".join(f"{x},{y}" for x, y in line["poly"])
        tip = html.escape(line["text"], quote=True)
        if entry is None:
            shapes.append(f'<polygon class="rest" points="{points}"><title>未归入菜品：{tip}</title></polygon>')
            continue
        hue = HUES[entry["item"] % len(HUES)] if entry["item"] >= 0 else 0
        dash = ' stroke-dasharray="8 6"' if entry["role"] == "detail" else ""
        shapes.append(
            f'<polygon class="box" data-item="{entry["item"]}" points="{points}" '
            f'style="fill:hsla({hue},85%,50%,{ROLE_FILL[entry["role"]]});stroke:hsl({hue},85%,38%)"{dash}>'
            f'<title>块 {entry["item"] + 1} · {ROLES[entry["role"]]}：{tip}</title></polygon>'
        )
    return "".join(shapes)


def rows_list(trace):
    rows = []
    for entry in trace:
        if entry is None:
            rows.append('<li class="sep">分隔行（表头、合计或支付）</li>')
        elif "role" in entry:
            rows.append(f'<li data-item="{entry["item"]}"><i>{ROLES[entry["role"]]}</i> '
                        f'{html.escape(entry["text"])}</li>')
        else:
            rows.append(f'<li class="rest"><i>未采用</i> {html.escape(entry["text"])}</li>')
    return "".join(rows)


def items_list(parsed):
    cards = []
    for index, item in enumerate(parsed["items"]):
        hue = HUES[index % len(HUES)]
        spec = f'<small>{html.escape(item["spec"])}</small>' if item["spec"] else ""
        cards.append(
            f'<li data-item="{index}" style="--hue:{hue}"><b>{html.escape(item["name"])}</b>{spec}'
            f'<span>× {item["qty"]} · {item["price"]:.2f}</span></li>'
        )
    return "".join(cards)


def summary(title, parsed, trace):
    last, first = receipt.layout_votes(trace)
    side = "金额在最后一行" if last > first else "金额在第一行"
    return (f'<div class="result"><h3>{html.escape(title)}</h3>'
            f'<p>判定：<b>{side}</b>（末行 {last} 票，首行 {first} 票）· 总额 {parsed["total"]}</p>'
            f'<ol class="items">{items_list(parsed)}</ol>'
            f'<details><summary>逐行结果</summary><ul class="rows">{rows_list(trace)}</ul></details></div>')


def section(run):
    original = cv2.imread(str(OUT / "originals" / f"{run}.jpg"))
    corrected = cv2.imread(str(ROOT / f"samples/run_20261102-{run}.jpg"))
    level = cv2.imread(str(COMPARE / "images" / f"phone_{run}_cascade.jpg"))
    phone = json.loads((ROOT / f"ocr_cache/app/run_20261102-{run}.json").read_text("utf8"))
    backend = phone.get("flatten_backend", "?")
    phone_lines = [line for line in phone["lines"] if line["text"].strip()]
    level_lines = json.loads((COMPARE / "ocr" / f"phone_{run}_cascade.json").read_text("utf8"))["lines"]
    parsed, trace = analyse(phone_lines)
    level_parsed, level_trace = analyse(level_lines)
    flat = trapezoid(original)
    panels = [panel("原图", "手机保存的 original.jpg", original)]
    panels.append(
        panel("① 梯形矫正", "电脑近似重做，手机没存四角", flat) if flat is not None
        else '<figure><figcaption><b>① 梯形矫正</b><span>电脑没找到四边，手机也没存四角</span></figcaption>'
             '<div class="empty">无中间图</div></figure>'
    )
    panels.append(panel(f"② 曲面矫正 {backend}", "手机 photo.jpg 与手机 OCR，当前 App 流程",
                        corrected, overlay_for(phone_lines, trace)))
    panels.append(panel("③ 文本行校平", "电脑校平并重跑 OCR，尚未进 App",
                        level, overlay_for(level_lines, level_trace)))
    return (f'<section><h2>小票 {run[:6]}</h2><div class="panels">{"".join(panels)}</div>'
            f'<div class="results">{summary("② 上的打块（手机数据）", parsed, trace)}'
            f'{summary("③ 上的打块（校平后）", level_parsed, level_trace)}</div></section>')


PAGE = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>打块结果</title><style>
:root{--bg:#f6f5f2;--card:#fff;--ink:#22221f;--dim:#6d6c66;--line:#dfddd6;color-scheme:light}
@media (prefers-color-scheme:dark){:root{--bg:#191917;--card:#252522;--ink:#ecebe6;--dim:#a3a29b;--line:#3b3a36;color-scheme:dark}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,"Microsoft YaHei",sans-serif}
header{padding:28px 16px 8px;max-width:1500px;margin:auto}h1{margin:0 0 4px;font-size:24px}header p{margin:0;color:var(--dim)}
.legend{display:flex;flex-wrap:wrap;gap:14px;margin-top:12px;color:var(--dim);font-size:13px}.legend i{display:inline-block;width:18px;height:11px;margin-right:5px;border:2px solid #2470c2;vertical-align:-1px}
section{max-width:1500px;margin:18px auto;padding:0 16px}h2{font-size:18px;margin:18px 0 10px}
.panels{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;align-items:start}
figure{margin:0;background:var(--card);border:1px solid var(--line);border-radius:8px;overflow:hidden}
figcaption{padding:8px 10px;min-height:62px}figcaption b{display:block}figcaption span{color:var(--dim);font-size:12.5px}
.stage{position:relative;line-height:0}.stage img{width:100%;height:auto}.stage svg{position:absolute;inset:0;width:100%;height:100%}
.box{stroke-width:3;transition:opacity .12s}.rest{fill:none;stroke:#8d8c86;stroke-width:1.5;opacity:.65}.dim .box{opacity:.12}.dim .box.on{opacity:1}
.empty{display:grid;place-items:center;height:220px;color:var(--dim)}
.results{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:12px}.result{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px}
h3{font-size:15px;margin:0 0 4px}.result p{margin:0 0 10px;color:var(--dim);font-size:13.5px}.items{list-style:none;margin:0;padding:0;display:grid;gap:6px}
.items li{border-left:5px solid hsl(var(--hue),85%,45%);background:hsla(var(--hue),85%,50%,.1);padding:6px 9px;border-radius:0 5px 5px 0;cursor:default;display:grid;grid-template-columns:1fr auto;column-gap:10px}
.items small{grid-column:1;color:var(--dim)}.items span{grid-row:1;grid-column:2;white-space:nowrap}
details{margin-top:10px}summary{cursor:pointer;color:var(--dim);font-size:13.5px}.rows{list-style:none;padding:0;margin:8px 0 0;font-size:13px}.rows li{padding:2px 0;overflow-wrap:anywhere}.rows i{font-style:normal;color:var(--dim);margin-right:5px}.rows .sep{color:var(--dim);border-top:1px dashed var(--line);margin-top:4px;padding-top:4px}.rows .rest{color:var(--dim)}
@media (max-width:900px){.panels{grid-template-columns:repeat(2,minmax(0,1fr))}.results{grid-template-columns:1fr}}
@media (max-width:480px){.panels{grid-template-columns:1fr}}
</style></head><body><header><h1>打块结果</h1><p>同色框属于同一个菜品块。把鼠标移到右侧菜品或图上的框，可以单独高亮这一块。</p>
<div class="legend"><span><i style="background:rgba(36,112,194,.42)"></i>菜名</span><span><i style="background:rgba(36,112,194,.34)"></i>菜名＋金额同一行</span><span><i style="background:rgba(36,112,194,.14)"></i>金额行</span><span><i style="border-style:dashed"></i>规格 / 修饰</span><span><i style="border-color:#8d8c86;border-width:1px"></i>未归入菜品</span></div></header>
__SECTIONS__<script>
for(const result of document.querySelectorAll('section')){
  const pairs=[...result.querySelectorAll('figure')].slice(2);
  result.querySelectorAll('.result').forEach((side,index)=>{
    const svg=pairs[index]&&pairs[index].querySelector('svg');if(!svg)return;
    const show=item=>{svg.classList.toggle('dim',item!==null);svg.querySelectorAll('.box').forEach(box=>box.classList.toggle('on',box.dataset.item===item))};
    side.querySelectorAll('[data-item]').forEach(row=>{row.addEventListener('mouseenter',()=>show(row.dataset.item));row.addEventListener('mouseleave',()=>show(null))});
    svg.querySelectorAll('.box').forEach(box=>{box.addEventListener('mouseenter',()=>show(box.dataset.item));box.addEventListener('mouseleave',()=>show(null))});
  });
}
</script></body></html>"""


def main():
    (OUT / "index.html").write_text(PAGE.replace("__SECTIONS__", "".join(section(run) for run in RUNS)), "utf8")
    print(OUT / "index.html")


if __name__ == "__main__":
    main()
