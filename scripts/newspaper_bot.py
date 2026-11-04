#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات روزنامه‌ی آسان نت

حالت fetch (هر روز ۷:۳۰ صبح تهران):
    صفحه‌ی https://www.jaaar.com/kiosk رو می‌خونه، لینک عکس صفحه‌ی اول روزنامه‌های «امروز» رو
    درمیاره و تو content.json یک آیتم «روزنامه» می‌سازه (یا آپدیت می‌کنه).
    - جمعه‌ها کاری نمی‌کنه.
    - تعطیلات رسمی رو از روی خودِ سایت تشخیص می‌ده: تاریخِ داخل آدرس عکس‌ها (مثل .../pishkhan/1405/7/26/...)
      باید با تاریخ امروز یکی باشه؛ وگرنه یعنی امروز روزنامه‌ای چاپ نشده و چیزی اضافه نمی‌شه.

حالت clean (هر شب ۲۳:۳۰ تهران):
    همه‌ی آیتم‌های روزنامه‌ای که خودِ این ربات ساخته پاک می‌شن.
    فقط آیتم‌هایی با شناسه‌ی item_newsauto_... پاک می‌شن؛ روزنامه‌هایی که خودت دستی تو پنل
    می‌ذاری هیچ‌وقت دست نمی‌خورن.

فقط «لینک» عکس‌ها تو content.json نوشته می‌شه؛ خود عکس‌ها تو گیت‌هاب ذخیره نمی‌شن.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone

KIOSK_URL = "https://www.jaaar.com/kiosk"
IMAGE_HOST = "https://static.jaaar.com"
BOT_ID_PREFIX = "item_newsauto_"

# سایت از هر روزنامه دو نسخه می‌ذاره: بندانگشتی کم‌کیفیت (حدود ۴۰۰ پیکسل) و نسخه‌ی با کیفیت (حدود ۱۲۰۰ پیکسل).
# فقط عکس‌هایی که ضلع بزرگ‌ترشون حداقل این‌قدر باشه نگه داشته می‌شن.
MIN_IMAGE_SIDE = 700
IMAGE_READ_BYTES = 256 * 1024

TEHRAN = timezone(timedelta(hours=3, minutes=30))  # ایران ساعت تابستانی نداره (UTC+3:30 ثابت)

MONTHS = ["فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
          "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"]
# پایتون: دوشنبه=۰ ... یکشنبه=۶
WEEKDAYS = ["دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"]
FRIDAY = 4

IMAGE_RE = re.compile(
    r"((?:https?:)?//[A-Za-z0-9.-]+(?::\d+)?)?"
    r"(/assets/images/pishkhan/(\d{4})/(\d{1,2})/(\d{1,2})/[0-9A-Za-z_-]+\.(?:jpg|jpeg|png|webp))"
)


# ───────────────────────── تبدیل تاریخ شمسی و میلادی ─────────────────────────

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


def jalali_to_gregorian(jy, jm, jd):
    jy += 1595
    days = (-355668 + 365 * jy + (jy // 33) * 8 + ((jy % 33) + 3) // 4 + jd
            + ((jm - 1) * 31 if jm < 7 else (jm - 7) * 30 + 186))
    gy = 400 * (days // 146097)
    days %= 146097
    if days > 36524:
        days -= 1
        gy += 100 * (days // 36524)
        days %= 36524
        if days >= 365:
            days += 1
    gy += 4 * (days // 1461)
    days %= 1461
    if days > 365:
        gy += (days - 1) // 365
        days = (days - 1) % 365
    gd = days + 1
    leap = (gy % 4 == 0 and gy % 100 != 0) or gy % 400 == 0
    sal_a = [0, 31, 29 if leap else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    gm = 0
    while gm < 13 and gd > sal_a[gm]:
        gd -= sal_a[gm]
        gm += 1
    return gy, gm, gd


def tehran_now():
    fake = os.environ.get("BOT_FAKE_NOW", "").strip()  # فقط برای تست
    if fake:
        return datetime.fromisoformat(fake).replace(tzinfo=TEHRAN)
    return datetime.now(TEHRAN)


# ───────────────────────── خواندن سایت ─────────────────────────

def fetch_html():
    local = os.environ.get("KIOSK_HTML_FILE", "").strip()  # فقط برای تست
    if local:
        with open(local, encoding="utf-8") as f:
            return f.read()
    headers = {
        "User-Agent": ("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                       "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"),
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "fa-IR,fa;q=0.9,en;q=0.8",
        "Accept-Encoding": "identity",
    }
    last_error = None
    for attempt in range(1, 4):
        try:
            request = urllib.request.Request(KIOSK_URL, headers=headers)
            with urllib.request.urlopen(request, timeout=40) as response:
                return response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            last_error = "HTTP %s" % e.code
        except Exception as e:  # noqa: BLE001
            last_error = type(e).__name__ + ": " + str(e)
        print("تلاش %d برای خواندن سایت ناموفق بود (%s)" % (attempt, last_error))
        time.sleep(10)
    print("::error::خواندن %s ممکن نشد (%s). ممکنه سایت درخواست‌های خارج از ایران رو رد کنه." %
          (KIOSK_URL, last_error))
    sys.exit(1)


def extract_groups(html):
    """لینک عکس‌ها رو برحسب تاریخ (از داخل آدرس) گروه‌بندی می‌کنه: {(سال, ماه, روز): [لینک‌ها]}"""
    text = (html.replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\/", "/").replace("&amp;", "&"))
    groups = {}
    seen = set()
    for m in IMAGE_RE.finditer(text):
        host, path = m.group(1), m.group(2)
        key = (int(m.group(3)), int(m.group(4)), int(m.group(5)))
        if host:
            base = ("https:" + host) if host.startswith("//") else host
        else:
            base = IMAGE_HOST
        url = base + path
        if url in seen:
            continue
        seen.add(url)
        groups.setdefault(key, []).append(url)
    return groups


# ───────────────────────── انتخاب نسخه‌ی با کیفیت ─────────────────────────

def _jpeg_size(b):
    if b[:2] != b"\xff\xd8":
        return None
    i, n = 2, len(b)
    while i + 9 < n:
        if b[i] != 0xFF:
            i += 1
            continue
        marker = b[i + 1]
        if marker == 0xFF:
            i += 1
            continue
        if marker in (0x01, 0xD8) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        seg_len = int.from_bytes(b[i + 2:i + 4], "big")
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height = int.from_bytes(b[i + 5:i + 7], "big")
            width = int.from_bytes(b[i + 7:i + 9], "big")
            return width, height
        i += 2 + seg_len
    return None


def _png_size(b):
    if b[:8] != b"\x89PNG\r\n\x1a\n" or len(b) < 24:
        return None
    return int.from_bytes(b[16:20], "big"), int.from_bytes(b[20:24], "big")


def _webp_size(b):
    if b[:4] != b"RIFF" or b[8:12] != b"WEBP" or len(b) < 30:
        return None
    kind = b[12:16]
    if kind == b"VP8X":
        return 1 + int.from_bytes(b[24:27], "little"), 1 + int.from_bytes(b[27:30], "little")
    if kind == b"VP8 ":
        return (int.from_bytes(b[26:28], "little") & 0x3FFF,
                int.from_bytes(b[28:30], "little") & 0x3FFF)
    if kind == b"VP8L":
        bits = int.from_bytes(b[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    return None


def image_size(url):
    """ابعاد (عرض، ارتفاع) عکس رو فقط با خوندن ابتدای فایل درمیاره؛ اگه نشد None."""
    try:
        request = urllib.request.Request(url, headers={
            "User-Agent": ("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                           "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"),
            "Referer": KIOSK_URL,
            "Accept": "image/*,*/*;q=0.8",
            "Accept-Encoding": "identity",
            "Range": "bytes=0-%d" % (IMAGE_READ_BYTES - 1),
        })
        with urllib.request.urlopen(request, timeout=30) as response:
            head = response.read(IMAGE_READ_BYTES)
        return _jpeg_size(head) or _png_size(head) or _webp_size(head)
    except Exception:  # noqa: BLE001
        return None


def keep_high_quality(urls):
    """از هر روزنامه فقط نسخه‌ی با کیفیت رو نگه می‌داره (کم‌کیفیت‌ها کنار گذاشته می‌شن)."""
    with ThreadPoolExecutor(max_workers=8) as pool:
        sizes = dict(zip(urls, pool.map(image_size, urls)))
    known = [u for u in urls if sizes[u]]
    if not known:
        print("::warning::ابعاد هیچ عکسی خونده نشد؛ همه‌ی لینک‌ها نگه داشته شد.")
        return urls
    kept, dropped = [], []
    for u in urls:
        size = sizes[u]
        if size is None or max(size) >= MIN_IMAGE_SIDE:
            kept.append(u)  # اگه ابعادش معلوم نشد، برای اینکه روزنامه‌ای گم نشه نگهش می‌داریم
        else:
            dropped.append(u)
    for u in dropped:
        print("کم‌کیفیت، کنار گذاشته شد (%dx%d): %s" % (sizes[u][0], sizes[u][1], u))
    unknown = [u for u in kept if sizes[u] is None]
    if unknown:
        print("::warning::ابعاد %d عکس معلوم نشد و نگه داشته شد." % len(unknown))
    if not kept:
        print("::warning::همه‌ی عکس‌ها کم‌کیفیت تشخیص داده شدن؛ همه نگه داشته شد.")
        return urls
    print("نسخه‌ی با کیفیت: %d عکس نگه داشته شد، %d عکس کم‌کیفیت حذف شد." % (len(kept), len(dropped)))
    return kept


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


# ───────────────────────── حالت‌ها ─────────────────────────

def run_clean(path):
    data, nl = load_content(path)
    kept = [it for it in data if not is_bot_item(it)]
    removed = len(data) - len(kept)
    if removed:
        save_content(path, kept, nl)
    print("پاک‌سازی: %d آیتم روزنامه‌ی خودکار حذف شد." % removed)


def run_fetch(path, ignore_date):
    now = tehran_now()
    today_j = gregorian_to_jalali(now.year, now.month, now.day)

    if now.weekday() == FRIDAY and not ignore_date:
        print("امروز جمعه‌ست؛ روزنامه چاپ نمی‌شه. کاری انجام نشد.")
        return

    groups = extract_groups(fetch_html())
    if not groups:
        print("::warning::هیچ لینک عکس روزنامه‌ای تو صفحه پیدا نشد (تعطیلیه یا ساختار سایت عوض شده).")
        return
    print("تاریخ‌های پیدا شده تو سایت:",
          ", ".join("%d/%d/%d (%d عکس)" % (k + (len(v),)) for k, v in sorted(groups.items())))

    if today_j in groups:
        chosen = today_j
    elif ignore_date:
        chosen = max(groups)
        print("حالت تست: به‌جای امروز، تاریخ %d/%d/%d انتخاب شد." % chosen)
    else:
        print("عکس‌های امروز (%d/%d/%d) هنوز تو سایت نیست؛ احتمالاً تعطیله یا هنوز آپلود نشده. "
              "کاری انجام نشد." % today_j)
        return

    jy, jm, jd = chosen
    urls = keep_high_quality(groups[chosen])[:100]
    gy, gm, gd = jalali_to_gregorian(jy, jm, jd)
    weekday = WEEKDAYS[date(gy, gm, gd).weekday()]
    item_id = "%s%04d%02d%02d" % (BOT_ID_PREFIX, jy, jm, jd)

    data, nl = load_content(path)
    # روزنامه‌های خودکارِ روزهای قبل (اگه پاک‌سازی شب رد شده باشه) هم جمع می‌شن
    data = [it for it in data if not (is_bot_item(it) and it.get("id") != item_id)]

    item = None
    for it in data:
        if it.get("id") == item_id:
            item = it
            break
    if item is None:
        item = {
            "id": item_id,
            "type": "newspaper",
            "title": weekday,
            "description": "صفحه نخست روزنامه های کشور %s %d %s %d" % (weekday, jd, MONTHS[jm - 1], jy),
            "url": "",
        }
        data.append(item)
    item["images"] = urls
    save_content(path, data, nl)
    print("آیتم روزنامه‌ی %s %d %s %d با %d عکس ذخیره شد." % (weekday, jd, MONTHS[jm - 1], jy, len(urls)))


def main():
    path = os.environ.get("CONTENT_PATH", "content.json")
    mode = os.environ.get("MODE", "fetch").strip().lower()
    ignore_date = os.environ.get("IGNORE_DATE", "false").strip().lower() == "true"
    if mode == "clean":
        run_clean(path)
    else:
        run_fetch(path, ignore_date)


if __name__ == "__main__":
    main()
