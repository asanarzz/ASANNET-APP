#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات «قیمت کالاهای اساسی» آسان نت (نسخه‌ی خودکارِ ابزار نبض قیمت بازار)

هیچ عکسی تو گیت‌هاب یا Supabase ذخیره نمی‌شه.

حالت fetch (هر روز ۷ صبح تهران):
  ۱) اسکرپر (scripts/nabze/scrape.mjs) قیمت‌ها رو از چند منبع می‌گیره؛ اگه منبعی خطا بده، منابع جایگزین و
     در نهایت داده‌ی آماده‌ی ابزار نبض بازار استفاده می‌شه.
  ۲) قیمت‌های تکراری مثل خودِ ابزار ادغام می‌شن (جدیدترین گزارش هر کالا) و همه‌ی کالاها تو یه لینک می‌رن.
  ۳) مطمئن می‌شه که صفحه‌ی nabze/index.html با اون لینک همه‌ی بنرها رو درست می‌سازه (هر بنر ۳۰ کالا)،
     و بعد یه آیتم تو دسته‌ی «دلار و طلا» / پوشه‌ی «کالاهای اساسی» اضافه می‌کنه.

حالت clean (هر شب ۲۳:۳۰ تهران): آیتم‌هایی که خودِ ربات ساخته (شناسه‌شون با item_nabzeauto_ شروع می‌شه) پاک می‌شن.
"""
import base64
import json
import os
import re
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone

VIEWER_BASE = os.environ.get("NABZE_VIEWER_URL", "https://asanarzz.github.io/ASANNET-APP/nabze/")
BOT_ID_PREFIX = "item_nabzeauto_"
SECTION_NAME = "کالاهای اساسی"
PER_PAGE = 30            # باید با PER_PAGE تو nabze/index.html یکی باشه
MIN_ITEMS = 30           # اگه کمتر از این تعداد کالا گرفته بشه، آیتمی اضافه نمی‌شه
CAT_ORDER = ["پروتئین", "لبنیات", "روغن", "برنج و غلات", "قند و شیرینی", "نوشیدنی", "حبوبات"]

TEHRAN = timezone(timedelta(hours=3, minutes=30))
MONTHS = ["فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
          "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"]
WEEKDAYS = ["دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"]
SCRAPER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "nabze")


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


def norm(text):
    """مثل norm تو اسکرپر: رقم‌های فارسی/عربی → لاتین، حذف جداکننده‌ی هزارگان."""
    t = str(text).translate(str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789"))
    t = re.sub(r"[٬،,]", "", t).replace("٫", ".")
    return t


def clean_unit(unit):
    """واحدهای هم‌معنی رو یکی می‌کنه: «هر کیلوگرم» = «کیلوگرم»؛ و واحدِ عددیِ اشتباه (مثل ۵۳۰,۰۰۰) رو خالی می‌کنه."""
    u = re.sub(r"\s+", " ", norm(unit)).strip()
    if re.fullmatch(r"[\d.\s]*", u):
        return ""
    return re.sub(r"^(هر|یک)\s+", "", u)


def item_key(x):
    return (re.sub(r"\s+", " ", norm(x.get("name", ""))).strip() + "|" +
            re.sub(r"\s+", "", clean_unit(x.get("unit", ""))))


def item_date(x):
    return x.get("reportDate") or x.get("date") or ""


def merge_items(items):
    """مثل تابع all() تو ابزار: برای هر کالا جدیدترین گزارش؛ اگه تاریخ‌ها برابر بود، قیمت بالاتر."""
    best = {}
    for x in items:
        try:
            lo, hi = float(x.get("min", 0)), float(x.get("max", 0))
        except (TypeError, ValueError):
            continue
        if not x.get("name") or lo <= 0 or hi < lo:
            continue
        k = item_key(x)
        cur = best.get(k)
        if cur is None or item_date(x) > item_date(cur) or (
                item_date(x) == item_date(cur) and hi > float(cur["max"])):
            best[k] = x
    # اگه یه کالا با همون قیمت دوباره (مثلاً با واحدِ ناقص) اومده بود، فقط یکی‌ش نگه داشته می‌شه
    unique = {}
    for x in best.values():
        k2 = (re.sub(r"\s+", " ", norm(x["name"])).strip(), int(round(float(x["max"]))))
        cur = unique.get(k2)
        if cur is None or (not clean_unit(cur.get("unit", "")) and clean_unit(x.get("unit", ""))):
            unique[k2] = x
    return list(unique.values())


def sort_items(items):
    def cat_index(x):
        c = x.get("cat", "")
        return CAT_ORDER.index(c) if c in CAT_ORDER else len(CAT_ORDER)
    return sorted(items, key=lambda x: (cat_index(x), x["name"]))


def build_link(items, timestamp):
    units, cats = [], []
    rows = []
    base = date(1970, 1, 1)
    today_num = (datetime.fromtimestamp(timestamp, TEHRAN).date() - base).days
    for x in items:
        unit = str(x.get("unit", ""))
        cat = x.get("cat", "") if x.get("cat", "") in CAT_ORDER else "سایر"
        if unit not in units:
            units.append(unit)
        if cat not in cats:
            cats.append(cat)
        try:
            day = (date.fromisoformat(item_date(x)[:10]) - base).days
        except ValueError:
            day = today_num
        rows.append([x["name"], units.index(unit), int(round(float(x["max"]))), cats.index(cat), day])
    payload = json.dumps({"t": timestamp, "u": units, "c": cats, "n": rows},
                         ensure_ascii=False, separators=(",", ":"))
    token = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    return VIEWER_BASE + "#d=" + token


# ───────────────────────── گرفتن داده و بررسی صفحه‌ی نمایش ─────────────────────────

def load_scraped():
    path = os.environ.get("NABZE_DATA_FILE", "").strip()  # فقط برای تست: بدون اجرای اسکرپر
    if not path:
        path = os.path.join(SCRAPER_DIR, "data", "scraped.json")
        print("اجرای اسکرپر...")
        result = subprocess.run(["node", "scrape.mjs"], cwd=SCRAPER_DIR, timeout=1500)
        if result.returncode != 0 or not os.path.exists(path):
            raise RuntimeError("اجرای اسکرپر ناموفق بود.")
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    for entry in data.get("log", []):
        print("  [%s] %s — %s" % (entry.get("status"), entry.get("name"), str(entry.get("msg"))[:90]))
    return data.get("items", [])


def save_debug(page):
    try:
        page.screenshot(path="debug-page.png", full_page=False)
        print("متن صفحه موقع خطا:\n" + page.evaluate("document.body.innerText.slice(0, 600)"))
    except Exception:  # noqa: BLE001
        pass


def verify_viewer(link, expected_pages):
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser = p.chromium.launch()
        try:
            context = browser.new_context(timezone_id="Asia/Tehran", locale="fa-IR",
                                          viewport={"width": 420, "height": 900})
            page = context.new_page()
            try:
                page.goto(link, wait_until="networkidle", timeout=90000)
                page.wait_for_function("window.__nabze && (window.__nabze.done || window.__nabze.error)",
                                       timeout=180000)
                info = page.evaluate(
                    "({err: window.__nabze.error, n: document.querySelectorAll('#out img').length, "
                    "w: [...document.querySelectorAll('#out img')].map(i => i.naturalWidth)})")
                if info["err"]:
                    raise RuntimeError(info["err"])
                if info["n"] != expected_pages or any(w != 1200 for w in info["w"]):
                    raise RuntimeError("تعداد یا اندازه‌ی بنرها درست نیست: %s (انتظار %d بنر)" % (info, expected_pages))
                print("%d بنر تو صفحه‌ی نمایش درست ساخته شد." % info["n"])
            except Exception:
                save_debug(page)
                raise
        except Exception as e:
            raise RuntimeError(
                "صفحه‌ی نمایش بنر (%s) درست کار نکرد: %s — اگه همین الان فایل nabze/index.html رو آپلود "
                "کردی، یکی دو دقیقه صبر کن تا GitHub Pages منتشرش کنه و دوباره اجرا کن." % (VIEWER_BASE, e))
        finally:
            browser.close()


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
    print("پاک‌سازی: %d آیتم کالاهای اساسی خودکار حذف شد." % removed)


def run_fetch(path):
    now = tehran_now()
    items = sort_items(merge_items(load_scraped()))
    print("کالاهای معتبر بعد از ادغام: %d" % len(items))
    if len(items) < MIN_ITEMS:
        raise RuntimeError("فقط %d کالا گرفته شد (حداقل %d لازمه)؛ آیتمی اضافه نشد." % (len(items), MIN_ITEMS))
    link = build_link(items, int(now.timestamp()))
    pages = -(-len(items) // PER_PAGE)
    print("طول لینک: %d کاراکتر، تعداد بنر: %d" % (len(link), pages))
    verify_viewer(link, pages)

    jy, jm, jd = gregorian_to_jalali(now.year, now.month, now.day)
    day_tag = "%04d%02d%02d" % (jy, jm, jd)
    item_id = BOT_ID_PREFIX + day_tag
    data, newline = load_content(path)
    data = [it for it in data if not (is_bot_item(it) and it.get("id") != item_id)]
    item = next((it for it in data if it.get("id") == item_id), None)
    if item is None:
        item = {"id": item_id, "type": "price"}
        data.append(item)
    item["type"] = "price"
    item["title"] = "قیمت کالاهای اساسی — %d قلم" % len(items)
    item["description"] = "%s %d %s %d — ساعت %02d:%02d" % (
        WEEKDAYS[now.weekday()], jd, MONTHS[jm - 1], jy, now.hour, now.minute)
    item["url"] = link
    item["section"] = SECTION_NAME
    item["allowExternalBrowser"] = False
    item["desktopMode"] = False
    save_content(path, data, newline)
    print("آیتم کالاهای اساسی (%d قلم، %d بنر) اضافه شد." % (len(items), pages))


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
