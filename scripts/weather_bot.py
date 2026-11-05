#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات آب‌وهوای آسان نت

سه حالت (متغیر MODE):
  generate  ← ۰۷:۳۰ تهران: صفحه‌ی weather-bot/weather_banner.html رو تو مرورگر بی‌سر باز می‌کنه،
              آب‌وهوای همه‌ی شهرها رو می‌گیره، عکس بنرها (هر عکس ≤۱۵ شهر) رو می‌سازه و
              روی سرویس عکس (imgbb یا litterbox) آپلود می‌کنه. خروجی فقط یه فایل JSON موقته‌ی «لینک‌ها»ست
              (تو مخزن ذخیره نمی‌شه). هیچ عکسی تو گیت‌هاب نمی‌مونه.
  apply     ← لینک‌ها رو (برای هر استان یک آیتم نوع weather) تو content.json می‌نویسه.
  clean     ← ۲۳:۳۰ تهران: همه‌ی آیتم‌هایی که شناسه‌شون با item_weatherauto_ شروع می‌شه پاک می‌شن.
              آیتم‌هایی که خودت دستی تو پنل گذاشتی (مثل لینک صفحه‌ی آب‌وهوا) هیچ‌وقت دست نمی‌خورن.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newspaper_bot import MONTHS, gregorian_to_jalali, load_content, save_content, tehran_now  # noqa: E402

BOT_ID_PREFIX = "item_weatherauto_"
PAGE = os.environ.get("WEATHER_PAGE", "weather-bot/weather_banner.html")
RESULT = os.environ.get("WEATHER_RESULT", "weather_result.json")
IMGBB_KEY = os.environ.get("IMGBB_API_KEY", "").strip()
DRY = os.environ.get("WEATHER_DRY_RUN", "").strip() == "1"   # فقط برای تست: آپلود نکن
MIN_OK = 0.95   # اگه کمتر از این نسبتِ عکس‌ها آپلود بشه، کار ناموفق حساب می‌شه و content.json دست نمی‌خوره

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"


# ───────────────────────── آپلود ─────────────────────────

def _multipart(fields, files):
    boundary = "----asn" + uuid.uuid4().hex
    body = b""
    for k, v in fields.items():
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, k, v)).encode()
    for k, (fname, data, ctype) in files.items():
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n"
                 % (boundary, k, fname, ctype)).encode() + data + b"\r\n"
    body += ("--%s--\r\n" % boundary).encode()
    return body, "multipart/form-data; boundary=" + boundary


def _post(url, fields, files, timeout=90):
    body, ctype = _multipart(fields, files)
    req = urllib.request.Request(url, data=body, headers={"Content-Type": ctype, "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def up_imgbb(png, name):
    # عکس خودش بعد از ۱۸ ساعت از imgbb پاک می‌شه (expiration به ثانیه)
    url = "https://api.imgbb.com/1/upload?expiration=64800&key=" + urllib.parse.quote(IMGBB_KEY)
    out = json.loads(_post(url, {}, {"image": (name, png, "image/png")}))
    if not out.get("success"):
        raise RuntimeError("imgbb: %s" % str(out)[:200])
    return out["data"]["url"]


def up_litterbox(png, name):
    # بدون کلید؛ فایل بعد از ۲۴ ساعت خودکار از سرویس پاک می‌شه
    out = _post("https://litterbox.catbox.moe/resources/internals/api.php",
                {"reqtype": "fileupload", "time": "24h"}, {"fileToUpload": (name, png, "image/png")}).strip()
    if not out.startswith("http"):
        raise RuntimeError("litterbox: %s" % out[:200])
    return out


def upload(png, name):
    hosts = ([("imgbb", up_imgbb)] if IMGBB_KEY else []) + [("litterbox", up_litterbox)]
    last = None
    for attempt in range(3):
        for hname, fn in hosts:
            try:
                return fn(png, name)
            except Exception as e:  # noqa: BLE001
                last = "%s: %s" % (hname, e)
        time.sleep(4 * (attempt + 1))
    raise RuntimeError(last)


# ───────────────────────── generate ─────────────────────────

def run_generate():
    from playwright.sync_api import sync_playwright

    images = []

    def emit(payload):
        info = json.loads(payload)
        info["png"] = base64.b64decode(info.pop("data").split(",", 1)[1])
        images.append(info)

    page_url = "file://" + os.path.abspath(PAGE) + "?auto=1"
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1088, "height": 1800})
        page.expose_function("asnEmit", emit)
        page.on("pageerror", lambda e: print("خطای صفحه:", e))
        page.goto(page_url, wait_until="load", timeout=120000)
        page.wait_for_function("typeof html2canvas!=='undefined'", timeout=60000)
        page.evaluate("document.fonts.ready.then(()=>1)")
        stats = page.evaluate("ASN_AUTO()")
        browser.close()
    print("ساخته شد: %d عکس برای %d شهر (بدون دما: %d)" % (stats["total"], stats["cities"], stats["missing"]))
    if stats["cities"] < 900 or not images:
        print("::error::تعداد شهرهای دارای دما خیلی کمه؛ احتمالاً Open-Meteo جواب نداده.")
        sys.exit(1)

    results, failed = [], 0
    for im in images:
        name = "weather-%03d.png" % im["n"]
        if DRY:
            url = "https://example.invalid/" + name
        else:
            try:
                url = upload(im["png"], name)
            except Exception as e:  # noqa: BLE001
                failed += 1
                print("::warning::آپلود %s ناموفق: %s" % (name, e))
                continue
        results.append({"n": im["n"], "prov": im["prov"], "part": im["part"], "parts": im["parts"], "url": url})
        print("آپلود شد %d/%d: %s" % (im["n"], im["total"], url))
    if not results or len(results) < MIN_OK * len(images):
        print("::error::فقط %d از %d عکس آپلود شد؛ چیزی در content.json نوشته نمی‌شه." % (len(results), len(images)))
        sys.exit(1)
    with open(RESULT, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False)
    print("لینک‌ها در %s ذخیره شد (%d لینک، %d ناموفق)." % (RESULT, len(results), failed))


# ───────────────────────── apply / clean ─────────────────────────

def is_bot_item(it):
    return str(it.get("id", "")).startswith(BOT_ID_PREFIX)


def run_apply(path):
    with open(RESULT, encoding="utf-8") as f:
        results = json.load(f)
    now = tehran_now()
    jy, jm, jd = gregorian_to_jalali(now.year, now.month, now.day)
    stamp = "%d %s %d" % (jd, MONTHS[jm - 1], jy)
    data, nl = load_content(path)
    data = [it for it in data if not is_bot_item(it)]   # آیتم‌های خودکار روزهای قبل (اگه پاک‌سازی شب رد شده)
    order, by_prov = [], {}
    for r in sorted(results, key=lambda r: r["n"]):
        if r["prov"] not in by_prov:
            by_prov[r["prov"]] = []
            order.append(r["prov"])
        by_prov[r["prov"]].append(r["url"])
    for i, prov in enumerate(order, 1):
        label = "استان " + prov if prov and prov != "سایر شهرها" else (prov or "شهرهای ایران")
        data.append({
            "id": "%s%04d%02d%02d_%02d" % (BOT_ID_PREFIX, jy, jm, jd, i),
            "type": "weather",
            "title": prov or "شهرهای ایران",
            "description": "آب و هوای شهرهای %s - %s" % (label, stamp),
            "url": "",
            "images": by_prov[prov],
        })
    save_content(path, data, nl)
    print("%d آیتم آب‌وهوا (%d عکس) در content.json نوشته شد." % (len(order), len(results)))


def run_clean(path):
    data, nl = load_content(path)
    kept = [it for it in data if not is_bot_item(it)]
    if len(kept) != len(data):
        save_content(path, kept, nl)
    print("پاک‌سازی: %d آیتم آب‌وهوای خودکار حذف شد." % (len(data) - len(kept)))


def main():
    path = os.environ.get("CONTENT_PATH", "content.json")
    mode = os.environ.get("MODE", "generate").strip().lower()
    {"generate": run_generate, "apply": lambda: run_apply(path), "clean": lambda: run_clean(path)}[mode]()


if __name__ == "__main__":
    main()
