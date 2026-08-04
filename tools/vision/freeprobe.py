#!/usr/bin/env python3
"""Перемер бесплатных зрячих провайдеров: кто прочитал, за сколько, насколько надёжно.

Отвечает на вопрос «кто сейчас читает даром», а не «кто читал в прошлый раз».
Список бесплатных провайдеров — протухающие данные, и протухает он в обе стороны:
мёртвые оживают, живые тихо упираются в лимит. Поэтому — замер, а не память.

Приватность здесь НЕ фильтр: меряются все, кто отвечает. Кому можно отдать объект,
решает человек уровнем приватности в приложении, а не автор таблицы заранее.

    python tools/vision/freeprobe.py --images чистая=скан.png плохая=фото.jpg \
        --expect эталон.txt --reps 3 --out прогон.json

  --expect  — файл с контрольными кусками текста, по одному на строку; счёт = сколько
              из них дословно нашлось в ответе.
  --reps    — повторов на каждую пару (кандидат, картинка). Повторяемость — половина
              ответа: одиночный удачный прогон прячет лимит провайдера.

Ключи берутся из local.properties (git-ignored) или из окружения. В вывод они не
попадают ни при каком исходе — печатаются только имена, коды и числа.
"""
import argparse, base64, json, mimetypes, os, re, sys, threading, time
import urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

# Без браузерного User-Agent часть провайдеров молча отвечает отказом, и это
# выглядит как «сервис умер». Дешёвая страховка, проверенная на Groq и Cerebras.
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

PROMPT = ("Прочитай изображение и выведи ВЕСЬ текст дословно, включая цифры и рукописные "
          "строки. Только текст, без комментариев.")

# Пауза между запросами ВНУТРИ провайдера: 429 должен быть лимитом провайдера,
# а не нашей собственной пачкой запросов.
PACE = {"OVH": 32.0, "OpenRouter": 3.0, "Zhipu": 3.0, "SambaNova": 2.0, "OCR.space": 3.0}


def load_keys(props="local.properties"):
    d = dict(os.environ)
    try:
        for ln in open(props, encoding="utf-8", errors="ignore"):
            if "=" in ln and not ln.lstrip().startswith("#"):
                k, v = ln.split("=", 1)
                d.setdefault(k.strip(), v.strip())
    except OSError:
        pass
    return d


class Ctx:
    """Картинки, ключи и счёт — всё, что нужно вызовам провайдеров."""

    def __init__(self, images, expect, keys):
        self.k = keys
        self.b64, self.mime, self.data = {}, {}, {}
        for name, path in images.items():
            raw = open(path, "rb").read()
            self.b64[name] = base64.b64encode(raw).decode()
            self.mime[name] = mimetypes.guess_type(path)[0] or "image/png"
            self.data[name] = f"data:{self.mime[name]};base64," + self.b64[name]
        self.expect = [l.strip() for l in open(expect, encoding="utf-8") if l.strip()]

    def score(self, txt):
        t = re.sub(r"\s+", " ", txt.replace(" ", " ").replace(" ", " ")
                   .replace(" ", " ")).lower()
        hit = [g for g in self.expect if re.sub(r"\s+", " ", g).lower() in t]
        return len(hit), len(self.expect), [g for g in self.expect if g not in hit]


def post(url, payload, headers, timeout=120):
    h = {"User-Agent": UA, "Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def oai(ctx, url, keyname, model, img):
    """Ручка формата OpenAI — на ней сидит большинство провайдеров."""
    h = {}
    key = ctx.k.get(keyname) if keyname else None
    if key:
        h["Authorization"] = "Bearer " + key
    r = post(url, {"model": model, "max_tokens": 2000, "temperature": 0,
                   "messages": [{"role": "user", "content": [
                       {"type": "text", "text": PROMPT},
                       {"type": "image_url", "image_url": {"url": ctx.data[img]}}]}]}, h)
    msg = r["choices"][0]["message"]
    txt = msg.get("content") or ""
    if isinstance(txt, list):
        txt = "".join(x.get("text", "") for x in txt)
    return txt or msg.get("reasoning_content", "")


def gemini(ctx, model, img):
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/{model}"
           f":generateContent?key={ctx.k.get('GEMINI_API_KEY', '')}")
    r = post(url, {"contents": [{"parts": [
        {"text": PROMPT},
        {"inline_data": {"mime_type": ctx.mime[img], "data": ctx.b64[img]}}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": 4000}}, {})
    parts = r["candidates"][0].get("content", {}).get("parts", [])
    return "".join(p.get("text", "") for p in parts)


def mistral_ocr(ctx, model, img):
    """Специальная OCR-ручка бьёт общий чат того же поставщика — это замерено."""
    r = post("https://api.mistral.ai/v1/ocr",
             {"model": model, "document": {"type": "image_url", "image_url": ctx.data[img]}},
             {"Authorization": "Bearer " + ctx.k.get("MISTRAL_API_KEY", "")})
    return "\n".join(pg.get("markdown", "") for pg in r.get("pages", []))


def ocrspace(ctx, img):
    import urllib.parse
    key = ctx.k.get("OCRSPACE_API_KEY") or "helloworld"  # helloworld = демо-ключ из их же примеров
    body = urllib.parse.urlencode({"apikey": key, "language": "rus", "OCREngine": "3",
                                   "base64Image": ctx.data[img], "isTable": "true"}).encode()
    req = urllib.request.Request("https://api.ocr.space/parse/image", data=body, headers={
        "User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=120) as r:
        j = json.loads(r.read().decode())
    if j.get("IsErroredOnProcessing"):
        raise RuntimeError(str(j.get("ErrorMessage"))[:200])
    return "\n".join(p.get("ParsedText", "") for p in j.get("ParsedResults", []))


GROQ = "https://api.groq.com/openai/v1/chat/completions"
CEREBRAS = "https://api.cerebras.ai/v1/chat/completions"
SAMBA = "https://api.sambanova.ai/v1/chat/completions"
MISTRAL = "https://api.mistral.ai/v1/chat/completions"
OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
OVH = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions"
ZHIPU = "https://api.z.ai/api/paas/v4/chat/completions"


def _oai(url, keyname, model):
    return lambda ctx, img: oai(ctx, url, keyname, model, img)


# (метка, провайдер, вызов). Провайдер = единица лимита, внутри него запросы идут по одному.
CANDIDATES = [
    ("Mistral OCR mistral-ocr-latest", "Mistral", lambda c, i: mistral_ocr(c, "mistral-ocr-latest", i)),
    ("Mistral OCR mistral-ocr-4-1", "Mistral", lambda c, i: mistral_ocr(c, "mistral-ocr-4-1", i)),
    ("Mistral chat mistral-medium-latest", "Mistral", _oai(MISTRAL, "MISTRAL_API_KEY", "mistral-medium-latest")),
    ("Mistral chat mistral-small-latest", "Mistral", _oai(MISTRAL, "MISTRAL_API_KEY", "mistral-small-latest")),
    ("Mistral chat ministral-14b-latest", "Mistral", _oai(MISTRAL, "MISTRAL_API_KEY", "ministral-14b-latest")),
    ("Gemini gemini-3.1-flash-lite", "Gemini", lambda c, i: gemini(c, "gemini-3.1-flash-lite", i)),
    ("Gemini gemini-3.5-flash", "Gemini", lambda c, i: gemini(c, "gemini-3.5-flash", i)),
    ("Gemini gemini-3.6-flash", "Gemini", lambda c, i: gemini(c, "gemini-3.6-flash", i)),
    ("Gemini gemini-flash-latest", "Gemini", lambda c, i: gemini(c, "gemini-flash-latest", i)),
    ("Gemini gemini-flash-lite-latest", "Gemini", lambda c, i: gemini(c, "gemini-flash-lite-latest", i)),
    ("Gemini gemma-4-31b-it", "Gemini", lambda c, i: gemini(c, "gemma-4-31b-it", i)),
    ("Groq qwen/qwen3.6-27b", "Groq", _oai(GROQ, "GROQ_API_KEY", "qwen/qwen3.6-27b")),
    ("Cerebras gemma-4-31b", "Cerebras", _oai(CEREBRAS, "CEREBRAS_API_KEY", "gemma-4-31b")),
    ("SambaNova gemma-4-31B-it", "SambaNova", _oai(SAMBA, "SAMBANOVA_API_KEY", "gemma-4-31B-it")),
    ("OpenRouter gemma-4-26b-a4b-it:free", "OpenRouter", _oai(OPENROUTER, "OPENROUTER_API_KEY", "google/gemma-4-26b-a4b-it:free")),
    ("OpenRouter nemotron-nano-12b-v2-vl:free", "OpenRouter", _oai(OPENROUTER, "OPENROUTER_API_KEY", "nvidia/nemotron-nano-12b-v2-vl:free")),
    ("OVH Qwen2.5-VL-72B (без ключа)", "OVH", _oai(OVH, None, "Qwen2.5-VL-72B-Instruct")),
    ("OVH Qwen3.6-27B (без ключа)", "OVH", _oai(OVH, None, "Qwen3.6-27B")),
    ("Zhipu glm-4.6v-flash", "Zhipu", _oai(ZHIPU, "ZHIPU_API_KEY", "glm-4.6v-flash")),
    ("OCR.space engine 3", "OCR.space", lambda c, i: ocrspace(c, i)),
]


def run_one(ctx, label, fn, img):
    t0 = time.time()
    try:
        txt = fn(ctx, img)
        hit, of, miss = ctx.score(txt)
        return {"label": label, "img": img, "sec": round(time.time() - t0, 1),
                "ok": True, "hit": hit, "of": of, "miss": miss, "text": txt}
    except urllib.error.HTTPError as e:
        return {"label": label, "img": img, "sec": round(time.time() - t0, 1), "ok": False,
                "err": f"HTTP {e.code}",
                "detail": e.read().decode(errors="ignore")[:220].replace("\n", " ")}
    except Exception as e:
        return {"label": label, "img": img, "sec": round(time.time() - t0, 1), "ok": False,
                "err": type(e).__name__, "detail": str(e)[:220]}


def summarise(rows):
    import statistics
    imgs = sorted({r["img"] for r in rows})
    print(f"\n{'кандидат':42s}" + "".join(f"{i:>22s}" for i in imgs) + "   ответов")
    for label in sorted({r["label"] for r in rows}):
        mine = [r for r in rows if r["label"] == label]
        cells = []
        for img in imgs:
            rs = [r for r in mine if r["img"] == img]
            oks = [r for r in rs if r["ok"]]
            if not oks:
                errs = ",".join(sorted({r.get("err", "?") for r in rs}))[:14]
                cells.append(f"{errs} 0/{len(rs)}".rjust(22))
                continue
            hits = [r["hit"] for r in oks]
            h = f"{min(hits)}/{oks[0]['of']}" if min(hits) == max(hits) \
                else f"{min(hits)}-{max(hits)}/{oks[0]['of']}"
            cells.append(f"{h} {statistics.median(r['sec'] for r in oks):5.1f}s "
                         f"{len(oks)}/{len(rs)}".rjust(22))
        ok = sum(1 for r in mine if r["ok"])
        print(f"{label[:42]:42s}" + "".join(cells) + f"   {ok}/{len(mine)}")
    print("\nячейка: попаданий/контрольных   медиана времени   ответов/попыток")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--images", nargs="+", required=True, metavar="ИМЯ=ПУТЬ")
    ap.add_argument("--expect", required=True, help="контрольные куски текста, по одному на строку")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--only", default="", help="подстрока метки: мерить только совпавших")
    ap.add_argument("--props", default="local.properties")
    ap.add_argument("--out", default="freeprobe.json")
    a = ap.parse_args()

    images = dict(kv.split("=", 1) for kv in a.images)
    ctx = Ctx(images, a.expect, load_keys(a.props))
    print(f"картинок {len(images)}, контрольных кусков {len(ctx.expect)}, повторов {a.reps}")

    by_prov = {}
    for label, prov, fn in CANDIDATES:
        if a.only.lower() in label.lower():
            by_prov.setdefault(prov, []).append((label, fn))
    if not by_prov:
        sys.exit("под --only никто не подошёл")

    rows, lock = [], threading.Lock()

    def worker(prov):
        pace = PACE.get(prov, 0.5)
        for rep in range(a.reps):
            for label, fn in by_prov[prov]:
                for img in images:
                    r = run_one(ctx, label, fn, img)
                    r["prov"], r["rep"] = prov, rep
                    with lock:
                        rows.append(r)
                        st = f"{r['hit']}/{r['of']}" if r["ok"] else r["err"]
                        print(f"{label[:40]:40s} {img:8s} #{rep} {r['sec']:6.1f}s {st}", flush=True)
                    time.sleep(pace)

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=len(by_prov)) as ex:
        list(ex.map(worker, by_prov))
    json.dump(rows, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    summarise(rows)
    print(f"\nготово за {time.time() - t0:.0f} c, замеров {len(rows)} -> {a.out}")


if __name__ == "__main__":
    main()
