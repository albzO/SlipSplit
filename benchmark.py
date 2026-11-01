"""Run every engine over the labelled samples through the running server.

Usage: python benchmark.py [engine,engine,...] [image_limit]
"""
import json
import sys
import urllib.request

BASE = "http://localhost:8700"


def post(path, body):
    request = urllib.request.Request(
        BASE + path, json.dumps(body).encode(), {"Content-Type": "application/json"},
    )
    return json.load(urllib.request.urlopen(request, timeout=1800))


config = json.load(urllib.request.urlopen(BASE + "/api/config"))
names = sys.argv[1].split(",") if len(sys.argv) > 1 else [
    e["id"] for e in config["engines"]
]
limit = int(sys.argv[2]) if len(sys.argv) > 2 else len(config["samples"])

print("engine,image,ok,load_ms,infer_ms,model_mb,lines,items,total,total_ok,matched,expected,f1,sum_ok")
for engine in names:
    for image in config["samples"][:limit]:
        r = post("/api/infer", {"image": image["id"], "engine": engine})
        if not r["ok"]:
            print(f'{engine},{image["name"]},ERROR,{r["error"][:300]}', flush=True)
            continue
        s = r.get("score", {})
        print(",".join(str(v) for v in (
            engine, image["name"], True, r["load_ms"], r["infer_ms"], r.get("model_mb"),
            len(r["lines"]), len(r["receipt"]["items"]), r["receipt"]["total"], s.get("total_ok"),
            s.get("items_matched"), s.get("items_expected"), s.get("item_f1"), r["sum_ok"],
        )), flush=True)
