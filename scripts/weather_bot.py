#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات آب‌وهوای آسان نت — مثل ربات قیمت: هیچ عکسی ساخته یا آپلود نمی‌شود.

حالت fetch (هر روز صبح):
    آب‌وهوای همه‌ی شهرهای weather-bot/weather_cities.json رو از Open-Meteo می‌گیره (رایگان، بدون کلید)
    و برای هر استان یک آیتم نوع weather تو content.json می‌سازه. آدرس آیتم یه «لینک صفحه» است:
        https://asanarzz.github.io/ASANNET-APP/weather/?s=<داده‌ی فشرده‌ی دماها>
    صفحه‌ی weather/index.html دما رو از همین لینک می‌خونه و بنرها (هر بنر ≤۱۵ شهر) رو تو خود برنامه نشون می‌ده.

حالت clean (هر شب ۲۳:۳۰ تهران):
    آیتم‌هایی که شناسه‌شون با item_weatherauto_ شروع می‌شه پاک می‌شن؛ آیتم‌های دستی دست نمی‌خورن.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newspaper_bot import MONTHS, gregorian_to_jalali, load_content, save_content, tehran_now  # noqa: E402

BOT_ID_PREFIX = "item_weatherauto_"
CITIES = os.environ.get("WEATHER_CITIES", "weather-bot/weather_cities.json")
COLORS = os.environ.get("WEATHER_COLORS", "weather-bot/weather_colors.json")   # اختیاری
VIEWER = os.environ.get("WEATHER_VIEWER_URL", "https://asanarzz.github.io/ASANNET-APP/weather/")
BATCH = 50
MIN_CITIES = 900   # اگه کمتر از این تعداد شهر دما داشته باشه، چیزی نوشته نمی‌شه


def fetch_batch(chunk):
    url = ("https://api.open-meteo.com/v1/forecast?latitude=" + ",".join(str(c[2]) for c in chunk)
           + "&longitude=" + ",".join(str(c[3]) for c in chunk)
           + "&current=temperature_2m,weather_code,cloud_cover&daily=temperature_2m_max,temperature_2m_min"
           "&timezone=Asia%2FTehran&forecast_days=1")
    last = None
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "asannet-weather-bot"})
            with urllib.request.urlopen(req, timeout=60) as r:
                d = json.loads(r.read().decode("utf-8"))
            return d if isinstance(d, list) else [d]
        except urllib.error.HTTPError as e:
            last = "HTTP %s" % e.code
            time.sleep(35 if e.code == 429 else 5 * (attempt + 1))
        except Exception as e:  # noqa: BLE001
            last = str(e)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(last)


def code_of(cur):
    code = cur.get("weather_code")
    if code is None:
        cc = cur.get("cloud_cover")
        code = -1 if cc is None else 0 if cc < 20 else 2 if cc < 60 else 3
    return code


def num(v):
    return int(round(v))


def is_bot_item(it):
    return str(it.get("id", "")).startswith(BOT_ID_PREFIX)


def link(prov, rows, ts, colors):
    payload = {"t": ts, "p": prov, "c": rows}
    if colors:
        payload["k"] = colors
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return VIEWER + "?s=" + base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def run_fetch(path):
    with open(CITIES, encoding="utf-8") as f:
        meta = json.load(f)
    cities = meta["cities"]
    got = {}
    for i in range(0, len(cities), BATCH):
        chunk = cities[i:i + BATCH]
        res = fetch_batch(chunk)
        for c, x in zip(chunk, res):
            cur, day = x.get("current") or {}, x.get("daily") or {}
            if cur.get("temperature_2m") is None or not day.get("temperature_2m_min"):
                continue
            got[(c[0], c[2], c[3])] = [c[0], num(cur["temperature_2m"]), num(day["temperature_2m_min"][0]),
                                      num(day["temperature_2m_max"][0]), code_of(cur)]
        print("دریافت دما: %d از %d" % (min(i + BATCH, len(cities)), len(cities)))
        time.sleep(1)
    print("دما برای %d شهر از %d شهر دریافت شد." % (len(got), len(cities)))
    if len(got) < MIN_CITIES:
        print("::error::تعداد شهرهای دارای دما خیلی کمه؛ احتمالاً Open-Meteo جواب نداده. چیزی نوشته نشد.")
        sys.exit(1)

    colors = None
    if os.path.exists(COLORS):
        with open(COLORS, encoding="utf-8") as f:
            colors = json.load(f) or None

    now = tehran_now()
    jy, jm, jd = gregorian_to_jalali(now.year, now.month, now.day)
    stamp = "%d %s %d" % (jd, MONTHS[jm - 1], jy)
    ts = int(now.timestamp())
    data, nl = load_content(path)
    data = [it for it in data if not is_bot_item(it)]
    n = 0
    for prov in meta["provinces"]:
        rows = [got[(c[0], c[2], c[3])] for c in cities if c[1] == prov and (c[0], c[2], c[3]) in got]
        if not rows:
            continue
        n += 1
        label = "استان " + prov if prov != "سایر شهرها" else prov
        data.append({
            "id": "%s%04d%02d%02d_%02d" % (BOT_ID_PREFIX, jy, jm, jd, n),
            "type": "weather",
            "title": prov,
            "description": "آب و هوای شهرهای %s - %s" % (label, stamp),
            "url": link(prov, rows, ts, colors),
            "allowExternalBrowser": False,
            "desktopMode": False,
        })
    save_content(path, data, nl)
    print("%d آیتم آب‌وهوا در content.json نوشته شد." % n)


def run_clean(path):
    data, nl = load_content(path)
    kept = [it for it in data if not is_bot_item(it)]
    if len(kept) != len(data):
        save_content(path, kept, nl)
    print("پاک‌سازی: %d آیتم آب‌وهوای خودکار حذف شد." % (len(data) - len(kept)))


def main():
    path = os.environ.get("CONTENT_PATH", "content.json")
    mode = os.environ.get("MODE", "fetch").strip().lower()
    run_clean(path) if mode == "clean" else run_fetch(path)


if __name__ == "__main__":
    main()
