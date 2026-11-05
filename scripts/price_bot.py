#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات قیمت دلار و طلای آسان نت

هیچ عکسی تو گیت‌هاب یا Supabase ذخیره نمی‌شه. کار ربات:

حالت fetch (۷ بار در روز: ۸ ، ۱۰ ، ۱۲ ، ۱۴ ، ۱۶ ، ۱۸ ، ۲۰ به وقت تهران):
  ۱) صفحه‌ی ابزار «بنرساز قیمت» رو تو یه مرورگر بدون صفحه باز می‌کنه و قیمت‌ها رو می‌گیره.
  ۲) از قیمت‌ها و زمانِ همین لحظه یه «لینک بنر» می‌سازه (قیمت‌ها داخل خودِ لینک هستن).
  ۳) مطمئن می‌شه که لینک واقعاً بنر رو نشون می‌ده (صفحه‌ی price/index.html بنر رو روی خودِ گوشی
     کاربر می‌کشه و به‌صورت عکس نشون می‌ده)، و بعد یه آیتم تو دسته‌ی «دلار و طلا» اضافه می‌کنه.

حالت clean (هر شب ۲۳:۳۰ تهران):
  همه‌ی آیتم‌های قیمتی که خودِ این ربات ساخته (شناسه‌شون با item_priceauto_ شروع می‌شه) پاک می‌شن.
  آیتم‌هایی که خودت دستی تو پنل می‌ذاری (مثل لینک خودِ ابزار بنرساز) هیچ‌وقت دست نمی‌خورن.
"""
import base64
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone

TOOL_URL = os.environ.get("PRICE_TOOL_URL", "https://asanarzz.github.io/ASANNETT/price-baner")
VIEWER_BASE = os.environ.get("PRICE_VIEWER_URL", "https://asanarzz.github.io/ASANNET-APP/price/")
BOT_ID_PREFIX = "item_priceauto_"

TEHRAN = timezone(timedelta(hours=3, minutes=30))  # ایران ساعت تابستانی نداره
MONTHS = ["فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
          "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"]
WEEKDAYS = ["دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"]

# آیتم‌های بنر (اسم‌ها همونیه که خودِ ابزار نشون می‌ده). ترتیب کارت‌ها همون ترتیب API می‌مونه.
WANTED_LABELS = ["USD USDT", "سکه امامی", "بهار آزادی", "نیم سکه", "ربع سکه",
                 "طلای ۱۸ عیار", "USD BTC", "دلار آمریکا"]
# اتریوم هم اگه تو لیست API بود اضافه می‌شه
ETH_RE = re.compile(r"(^|[\s_])eth($|[\s_])", re.IGNORECASE)
# اگه هیچ‌کدوم از آیتم‌های بالا پیدا نشد، همون پیش‌فرض‌های خودِ ابزار
FALLBACK_KEYS = ["usd", "18ayar", "sekkeh"]


# ───────────────────────── کمکی‌ها ─────────────────────────

def norm(text):
    """برای مقایسه‌ی اسم‌ها: ی/ک عربی، رقم‌های فارسی، نیم‌فاصله و فاصله‌ی اضافه رو یکی می‌کنه."""
    t = str(text).replace("ي", "ی").replace("ك", "ک").replace("\u200c", " ")
    t = t.translate(str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789"))
    return re.sub(r"\s+", " ", t).strip().lower()


def pick_items(items):
    """از لیست آیتم‌های API (key و label و value) آیتم‌های دلخواه رو به‌ترتیب API برمی‌گردونه."""
    wanted = {norm(label) for label in WANTED_LABELS}
    chosen = [it for it in items
              if norm(it.get("label", "")) in wanted
              or ETH_RE.search(str(it.get("key", ""))) or ETH_RE.search(str(it.get("label", "")))]
    if not chosen:
        chosen = [it for it in items if it.get("key") in FALLBACK_KEYS]
    return chosen


def clean_number(value):
    try:
        number = float(str(value).replace(",", "").strip())
    except ValueError:
        return None
    return int(number) if number.is_integer() else number


def build_link(items, timestamp):
    pairs = []
    for it in items:
        number = clean_number(it.get("value"))
        key = re.sub(r"[^a-z0-9_]", "", str(it.get("key", "")).lower())
        if number is not None and key:
            pairs.append([key, number])
    if not pairs:
        raise RuntimeError("هیچ قیمت معتبری برای ساخت لینک پیدا نشد.")
    payload = json.dumps({"t": timestamp, "i": pairs}, separators=(",", ":"))
    token = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    return VIEWER_BASE + "?s=" + token


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


# ───────────────────────── مرورگر ─────────────────────────

def new_context(browser, viewport=None):
    context = browser.new_context(timezone_id="Asia/Tehran", locale="fa-IR",
                                  viewport=viewport or {"width": 1100, "height": 1700})
    mock = os.environ.get("BOT_MOCK_NAVASAN_FILE", "").strip()  # فقط برای تست بدون اینترنت
    if mock:
        with open(mock, encoding="utf-8") as f:
            body = f.read()

        def handler(route):
            url = route.request.url
            if "api.navasan.tech" in url:
                route.fulfill(status=200, content_type="application/json", body=body)
            elif url.startswith("http"):
                route.abort()
            else:
                route.continue_()
        context.route("**/*", handler)
    return context


def collect_prices(playwright):
    """قیمت‌ها رو با خودِ ابزار (همون کدِ دریافت و تبدیل) می‌گیره و لیست آیتم‌ها رو برمی‌گردونه."""
    browser = playwright.chromium.launch()
    try:
        context = new_context(browser)
        page = context.new_page()
        try:
            print("باز کردن ابزار:", TOOL_URL)
            page.goto(TOOL_URL, wait_until="networkidle", timeout=90000)
            page.click("#fetchBtn")
            page.wait_for_function(
                "typeof currentPrices !== 'undefined' && currentPrices.length > 0", timeout=90000)
            return page.evaluate("currentPrices.map(i => ({key: i.key, label: i.label, value: i.value}))")
        except Exception:
            save_debug(page)
            raise
    finally:
        browser.close()


def verify_viewer(playwright, link):
    """لینک رو تو مرورگر باز می‌کنه و مطمئن می‌شه که بنر واقعاً به‌صورت عکس نشون داده می‌شه."""
    browser = playwright.chromium.launch()
    try:
        context = new_context(browser, viewport={"width": 420, "height": 900})
        page = context.new_page()
        try:
            page.goto(link, wait_until="networkidle", timeout=90000)
            page.wait_for_function(
                "document.getElementById('out') && document.getElementById('out').src.startsWith('data:image/png')",
                timeout=60000)
            size = page.evaluate("({w: out.naturalWidth, h: out.naturalHeight})")
            if size["w"] != 1080 or size["h"] < 800:
                raise RuntimeError("ابعاد بنر غیرعادیه: %s" % size)
            print("بنر تو صفحه‌ی نمایش درست ساخته شد (%dx%d)." % (size["w"], size["h"]))
        except Exception:
            save_debug(page)
            raise
    except Exception as e:
        raise RuntimeError(
            "صفحه‌ی نمایش بنر (%s) درست کار نکرد: %s — اگه همین الان فایل price/index.html رو آپلود "
            "کردی، یکی دو دقیقه صبر کن تا GitHub Pages منتشرش کنه و دوباره اجرا کن." % (VIEWER_BASE, e))
    finally:
        browser.close()


def save_debug(page):
    try:
        page.screenshot(path="debug-page.png", full_page=True)
        print("متن صفحه موقع خطا:\n" + page.evaluate("document.body.innerText.slice(0, 600)"))
    except Exception:  # noqa: BLE001
        pass


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
    from playwright.sync_api import sync_playwright

    now = tehran_now()
    with sync_playwright() as p:
        items = collect_prices(p)
        print("آیتم‌های موجود تو API (%d تا):" % len(items))
        for it in items:
            print("  %-22s %-24s %s" % (it["key"], it["label"], it["value"]))
        chosen = pick_items(items)
        if not chosen:
            raise RuntimeError("هیچ‌کدوم از آیتم‌های دلخواه تو لیست API پیدا نشد.")
        print("آیتم‌های انتخاب‌شده: %d تا -> %s" % (len(chosen), ", ".join(it["key"] for it in chosen)))
        link = build_link(chosen, int(now.timestamp()))
        print("طول لینک:", len(link))
        verify_viewer(p, link)

    jy, jm, jd = gregorian_to_jalali(now.year, now.month, now.day)
    day_tag = "%04d%02d%02d" % (jy, jm, jd)
    item_id = "%s%s_%02d%02d" % (BOT_ID_PREFIX, day_tag, now.hour, now.minute)
    clock = "%02d:%02d" % (now.hour, now.minute)

    data, newline = load_content(path)
    # بنرهای خودکارِ روزهای قبل (اگه پاک‌سازی شب رد شده باشه) هم جمع می‌شن
    data = [it for it in data if not (is_bot_item(it) and not str(it["id"]).startswith(BOT_ID_PREFIX + day_tag))]
    item = next((it for it in data if it.get("id") == item_id), None)
    if item is None:
        item = {"id": item_id, "type": "price"}
        data.append(item)
    item["type"] = "price"
    item["title"] = "قیمت دلار و طلا — ساعت " + clock
    item["description"] = "%s %d %s %d" % (WEEKDAYS[now.weekday()], jd, MONTHS[jm - 1], jy)
    item["url"] = link
    save_content(path, data, newline)
    print("آیتم قیمت ساعت %s اضافه شد." % clock)


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
