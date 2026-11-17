#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات قیمت زنده‌ی دلار و طلای آسان نت (جایگزین ربات قیمت قبلی)

هیچ عکسی تو گیت‌هاب یا Supabase ذخیره نمی‌شه. این ربات مرورگر هم لازم نداره، چون خودِ صفحه‌ی
«قیمت روز دلار و طلا» قیمت‌ها رو زنده می‌گیره (و دکمه‌ی دانلود عکس هم همون‌جاست). کار ربات:

حالت fetch:
  ۱) مطمئن می‌شه صفحه‌ی زنده‌ی قیمت واقعاً باز می‌شه (که آیتمِ خراب تو اپ نره).
  ۲) یه آیتم تو دسته‌ی «دلار و طلا» می‌سازه که همون صفحه رو باز می‌کنه
     (هر روز فقط یه آیتم؛ اگه از قبل باشه فقط به‌روز می‌شه).

حالت clean:
  همه‌ی آیتم‌هایی که خودِ این ربات ساخته (شناسه‌شون با item_pricelive_ شروع می‌شه) پاک می‌شن.
  آیتم‌های ربات‌های دیگه و آیتم‌هایی که خودت دستی تو پنل می‌ذاری هیچ‌وقت دست نمی‌خورن.
"""
import json
import os
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

LIVE_URL = os.environ.get("PRICE_LIVE_URL", "https://asanarzz.github.io/ASANNETT/price/")
BOT_ID_PREFIX = "item_pricelive_"
# دکمه‌ی «باز کردن در مرورگر بیرونی» روشنه تا اگه دانلود عکس تو خودِ اپ کار نکرد، بشه تو مرورگر بیرونی دانلودش کرد
ALLOW_EXTERNAL_BROWSER = True

TEHRAN = timezone(timedelta(hours=3, minutes=30))  # ایران ساعت تابستانی نداره
MONTHS = ["فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
          "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"]
WEEKDAYS = ["دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"]

# ───────────────────────── کمکی‌ها ─────────────────────────

def gregorian_to_jalali(gy, gm, gd):
    g_d_m = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
    if gy > 1600:
        jy = 979
        gy -= 1600
    else:
        jy = 0
        gy -= 621
    gy2 = gy + 1 if gm > 2 else gy
    days = (365 * gy + (gy2 + 3) // 4 - (gy2 + 99) // 100 + (gy2 + 399) // 400
            - 80 + gd + g_d_m[gm - 1])
    jy += 33 * (days // 12053)
    days %= 12053
    jy += 4 * (days // 1461)
    days %= 1461
    if days > 365:
        jy += (days - 1) // 365
        days = (days - 1) % 365
    if days < 186:
        jm = 1 + days // 31
        jd = 1 + days % 31
    else:
        jm = 7 + (days - 186) // 30
        jd = 1 + (days - 186) % 30
    return jy, jm, jd


def tehran_now():
    fake = os.environ.get("BOT_FAKE_NOW", "").strip()  # فقط برای تست
    if fake:
        return datetime.fromisoformat(fake).replace(tzinfo=TEHRAN)
    return datetime.now(TEHRAN)


# ───────────────────────── صفحه‌ی زنده ─────────────────────────

def check_live_page():
    """مطمئن می‌شه صفحه‌ی زنده‌ی قیمت واقعاً باز می‌شه."""
    if os.environ.get("BOT_SKIP_CHECK", "").strip():  # فقط برای تست بدون اینترنت
        print("بررسی صفحه رد شد (حالت تست).")
        return
    request = urllib.request.Request(LIVE_URL, headers={"User-Agent": "Mozilla/5.0 (asan-net price bot)"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            status = response.status
            html = response.read(500000).decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        raise RuntimeError("صفحه‌ی قیمت زنده (%s) باز نشد: %s" % (LIVE_URL, e))
    if status != 200 or "قیمت روز دلار و طلا" not in html:
        raise RuntimeError("صفحه‌ی قیمت زنده (%s) درست نیست (وضعیت %s)." % (LIVE_URL, status))
    print("صفحه‌ی قیمت زنده درست باز شد.")


# ───────────────────────── content.json ─────────────────────────

def load_content(path):
    with open(path, encoding="utf-8") as f:
        raw = f.read()
    return json.loads(raw), raw.endswith("\n")


def save_content(path, data, trailing_newline):
    text = json.dumps(data, ensure_ascii=False, indent=2)
    if trailing_newline:
        text += "\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def is_bot_item(item):
    return str(item.get("id", "")).startswith(BOT_ID_PREFIX)


def run_clean(path):
    data, newline = load_content(path)
    kept = [it for it in data if not is_bot_item(it)]
    removed = len(data) - len(kept)
    if removed:
        save_content(path, kept, newline)
    print("پاک‌سازی: %d آیتم قیمت خودکار حذف شد." % removed)


def run_fetch(path):
    check_live_page()

    now = tehran_now()
    jy, jm, jd = gregorian_to_jalali(now.year, now.month, now.day)
    day_tag = "%04d%02d%02d" % (jy, jm, jd)
    item_id = BOT_ID_PREFIX + day_tag

    data, newline = load_content(path)
    # آیتم‌های خودکارِ روزهای قبل (اگه پاک‌سازی شب رد شده باشه) هم جمع می‌شن
    data = [it for it in data if not (is_bot_item(it) and str(it["id"]) != item_id)]
    item = next((it for it in data if it.get("id") == item_id), None)
    if item is None:
        item = {"id": item_id, "type": "price"}
        data.append(item)
    item["type"] = "price"
    item["title"] = "قیمت دلار و طلا — زنده"
    item["description"] = "%s %d %s %d" % (WEEKDAYS[now.weekday()], jd, MONTHS[jm - 1], jy)
    item["url"] = LIVE_URL
    item["section"] = "دلار و طلا"  # پوشه‌ی داخل دسته‌ی «دلار و طلا»
    item["allowExternalBrowser"] = ALLOW_EXTERNAL_BROWSER
    item["desktopMode"] = False
    save_content(path, data, newline)
    print("آیتم قیمت زنده (%s) آماده شد." % item_id)


def main():
    path = os.environ.get("CONTENT_PATH", "content.json")
    if os.environ.get("MODE", "fetch").strip().lower() == "clean":
        run_clean(path)
    else:
        run_fetch(path)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print("::error::%s" % e)
        sys.exit(1)
