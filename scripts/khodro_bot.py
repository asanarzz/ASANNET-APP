#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات «قیمت روز خودرو» آسان نت (نسخه‌ی خودکارِ ابزار نبض قیمت خودرو)

هیچ عکسی تو گیت‌هاب یا Supabase ذخیره نمی‌شه.

حالت fetch (هر روز صبح):
  ۱) اسکرپر (scripts/scrape_cars.py) قیمت خودروها رو از چند سایت خبری می‌گیره.
  ۲) قیمت‌های خراب یا تکراری حذف می‌شن (جدیدترین گزارش هر خودرو) و همه‌ی خودروها تو یه لینک می‌رن.
  ۳) مطمئن می‌شه که صفحه‌ی khodro-viewer.html با اون لینک همه‌ی بنرها رو درست می‌سازه
     (بنرها به تفکیک شرکت: ایران‌خودرو، سایپا، سایر شرکت‌ها؛ هر بنر حداکثر ۳۰ خودرو)،
     و بعد یه آیتم تو دسته‌ی «دلار و طلا» / پوشه‌ی «قیمت خودرو» اضافه می‌کنه.

حالت clean (هر شب ۲۳:۳۰ تهران): آیتم‌هایی که خودِ ربات ساخته (شناسه‌شون با item_khodroauto_ شروع می‌شه) پاک می‌شن.
"""
import base64
import json
import math
import os
import re
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone

VIEWER_BASE = os.environ.get("KHODRO_VIEWER_URL", "https://asanarzz.github.io/ASANNET-APP/khodro-viewer.html")
BOT_ID_PREFIX = "item_khodroauto_"
SECTION_NAME = "قیمت خودرو"
PER_PAGE = 30            # باید با PER_PAGE تو khodro-viewer.html یکی باشه
MIN_ITEMS = 30           # اگه کمتر از این تعداد خودرو گرفته بشه، آیتمی اضافه نمی‌شه
MAX_AGE_DAYS = 4         # قیمتی که گزارشش قدیمی‌تر از این باشه کنار گذاشته می‌شه
MIN_PRICE = 500_000_000  # قیمت‌های کمتر/بیشتر از این بازه تقریباً حتماً خطای خوندن جدول هستن
MAX_PRICE = 25_000_000_000   # (مثلاً ۴۷ میلیارد برای جک J7 از اشتباهِ ستونِ یه سایت اومده بود؛ قیمت واقعی ۷ میلیاردِ)

# ترتیب گروه‌ها (همون گروه‌بندی اسکرپر)
CAT_ORDER = ["ایران‌خودرو", "سایپا", "مدیران خودرو", "بهمن موتور", "کرمان موتور", "وارداتی", "سایر"]
# بنر اول ایران‌خودرو، بنر دوم سایپا و بنر سوم بقیه‌ی شرکت‌ها (مثل ابزار نبض قیمت خودرو)
GROUPS = ["ایران‌خودرو", "سایپا", "سایر شرکت‌ها"]

TEHRAN = timezone(timedelta(hours=3, minutes=30))
MONTHS = ["فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
          "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"]
WEEKDAYS = ["دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"]
SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
SCRAPER = "scrape_cars.py"
SCRAPED_FILE = os.path.join(SCRIPTS_DIR, "khodro_scraped.json")


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
    """رقم‌های فارسی/عربی → لاتین، حذف جداکننده‌ی هزارگان."""
    t = str(text).translate(str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789"))
    t = re.sub(r"[٬،,]", "", t).replace("٫", ".")
    return t.replace("ي", "ی").replace("ك", "ک")


def clean_name(name):
    """اسم خودرو رو از آشغال جدول (مثل «نام خودروقیمت بازار (تومان)» که به اول اسم چسبیده) تمیز می‌کنه."""
    n = re.sub(r"^.*?قیمت\s*بازار\s*\(?\s*تومان\s*\)?", "", str(name))
    n = re.sub(r"^\s*نام\s*خودرو\s*", "", n)
    return re.sub(r"\s+", " ", n).strip(" -–:|·")


def name_key(name):
    return re.sub(r"[\s‌()\-–]", "", norm(name)).lower()


def item_date(x):
    return x.get("reportDate") or x.get("date") or ""


def valid_item(x, today):
    """فقط آیتم‌های سالم: اسم درست، قیمت منطقی و گزارش تازه."""
    name = x.get("name", "")
    if len(name) < 3 or not re.search(r"[آ-یA-Za-z]", name):
        return False
    if name.endswith("مدل"):  # اسمِ نصفه‌ی خونده‌شده از متن خبر؛ قیمتش هم معمولاً مال یه ستون دیگه‌ست
        return False
    try:
        price = float(x.get("price", 0))
        agency = float(x.get("agency", 0) or 0)
    except (TypeError, ValueError):
        return False
    if not (MIN_PRICE <= price <= MAX_PRICE):
        return False
    if agency and not (0.3 <= agency / price <= 2):
        x["agency"] = 0   # قیمت نمایندگیِ غیرمنطقی نشون داده نمی‌شه
    try:
        d = date.fromisoformat(item_date(x)[:10])
    except ValueError:
        return False
    return today - timedelta(days=MAX_AGE_DAYS) <= d <= today + timedelta(days=1)


def merge_items(items, today):
    """برای هر خودرو جدیدترین گزارش؛ اگه تاریخ‌ها برابر بود، قیمت بالاتر."""
    best = {}
    for x in items:
        x = dict(x)
        x["name"] = clean_name(x.get("name", ""))
        if not valid_item(x, today):
            continue
        k = name_key(x["name"])
        cur = best.get(k)
        if cur is None or item_date(x) > item_date(cur) or (
                item_date(x) == item_date(cur) and float(x["price"]) > float(cur["price"])):
            best[k] = x
    return list(best.values())


def cat_index(x):
    c = x.get("cat", "")
    return CAT_ORDER.index(c) if c in CAT_ORDER else len(CAT_ORDER) - 1


def group_index(x):
    c = x.get("cat", "")
    if c == GROUPS[0]:
        return 0
    if c == GROUPS[1]:
        return 1
    return 2


def sort_items(items):
    return sorted(items, key=lambda x: (group_index(x), cat_index(x), x["name"]))


def count_pages(items):
    """همون تقسیم‌بندیِ khodro-viewer.html: هر گروه یه یا چند بنر مساوی (حداکثر PER_PAGE تا تو هر بنر)."""
    total = 0
    for gi in range(len(GROUPS)):
        n = sum(1 for x in items if group_index(x) == gi)
        if n:
            total += math.ceil(n / PER_PAGE)
    return total


def build_link(items, timestamp):
    cats, rows = [], []
    base = date(1970, 1, 1)
    today_num = (datetime.fromtimestamp(timestamp, TEHRAN).date() - base).days
    for x in items:
        cat = x.get("cat", "") if x.get("cat", "") in CAT_ORDER else "سایر"
        if cat not in cats:
            cats.append(cat)
        try:
            day = (date.fromisoformat(item_date(x)[:10]) - base).days
        except ValueError:
            day = today_num
        rows.append([x["name"], int(round(float(x["price"]))), int(round(float(x.get("agency", 0) or 0))),
                     cats.index(cat), day, group_index(x)])
    payload = json.dumps({"t": timestamp, "c": cats, "g": GROUPS, "n": rows},
                         ensure_ascii=False, separators=(",", ":"))
    token = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    return VIEWER_BASE + "#d=" + token


# ───────────────────────── گرفتن داده و بررسی صفحه‌ی نمایش ─────────────────────────

def load_scraped():
    path = os.environ.get("KHODRO_DATA_FILE", "").strip()  # فقط برای تست: بدون اجرای اسکرپر
    if not path:
        path = SCRAPED_FILE
        if os.path.exists(path):
            os.remove(path)   # مطمئن می‌شیم داده‌ی قدیمیِ یه اجرای قبلی استفاده نمی‌شه
        print("اجرای اسکرپر...")
        env = dict(os.environ, KHODRO_OUT=path)
        result = subprocess.run([sys.executable, SCRAPER], cwd=SCRIPTS_DIR, env=env, timeout=1500)
        if result.returncode != 0 or not os.path.exists(path):
            raise RuntimeError("اجرای اسکرپر ناموفق بود.")
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    for entry in data.get("log", []):
        print("  [%s] %s — %s" % (entry.get("status"), entry.get("source"),
                                  str(entry.get("error") or entry.get("count"))[:90]))
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
                page.wait_for_function("window.__khodro && (window.__khodro.done || window.__khodro.error)",
                                       timeout=180000)
                info = page.evaluate(
                    "({err: window.__khodro.error, n: document.querySelectorAll('#out img').length, "
                    "w: [...document.querySelectorAll('#out img')].map(i => i.naturalWidth)})")
                if info["err"]:
                    raise RuntimeError(info["err"])
                if info["n"] != expected_pages or any(w != 1200 for w in info["w"]):
                    raise RuntimeError("تعداد یا اندازه‌ی بنرها درست نیست: %s (انتظار %d بنر)" % (info, expected_pages))
                print("%d بنر تو صفحه‌ی نمایش درست ساخته شد." % info["n"])
                shot = os.environ.get("KHODRO_SHOT_DIR", "").strip()  # فقط برای تست
                if shot:
                    for i, el in enumerate(page.query_selector_all("#out img")):
                        el.screenshot(path=os.path.join(shot, "banner-%d.png" % (i + 1)))
            except Exception:
                save_debug(page)
                raise
        except Exception as e:
            raise RuntimeError(
                "صفحه‌ی نمایش بنر (%s) درست کار نکرد: %s — اگه همین الان فایل khodro-viewer.html رو آپلود "
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
    print("پاک‌سازی: %d آیتم قیمت خودروی خودکار حذف شد." % removed)


def run_fetch(path):
    now = tehran_now()
    items = sort_items(merge_items(load_scraped(), now.date()))
    print("خودروهای معتبر بعد از تمیزکاری: %d" % len(items))
    if len(items) < MIN_ITEMS:
        raise RuntimeError("فقط %d خودرو گرفته شد (حداقل %d لازمه)؛ آیتمی اضافه نشد." % (len(items), MIN_ITEMS))
    link = build_link(items, int(now.timestamp()))
    pages = count_pages(items)
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
    item["title"] = "قیمت روز خودرو — %d خودرو" % len(items)
    item["description"] = "%s %d %s %d — ساعت %02d:%02d" % (
        WEEKDAYS[now.weekday()], jd, MONTHS[jm - 1], jy, now.hour, now.minute)
    item["url"] = link
    item["section"] = SECTION_NAME
    item["allowExternalBrowser"] = False
    item["desktopMode"] = False
    save_content(path, data, newline)
    print("آیتم قیمت خودرو (%d خودرو، %d بنر) اضافه شد." % (len(items), pages))


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
