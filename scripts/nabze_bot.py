#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ربات «قیمت کالاهای اساسی» آسان نت (نسخه‌ی خودکارِ ابزار نبض قیمت بازار)

هیچ عکسی تو گیت‌هاب یا Supabase ذخیره نمی‌شه.

حالت fetch (هر روز ۷ صبح تهران):
  ۱) اسکرپر (scripts/nabze/scrape.mjs) قیمت‌ها رو از چند منبع می‌گیره؛ اگه منبعی خطا بده، منابع جایگزین و
     در نهایت داده‌ی آماده‌ی ابزار نبض بازار استفاده می‌شه.
  ۲) قیمت‌های تکراری ادغام می‌شن و کالاها بر اساس «نوع» انتخاب می‌شن؛ همه‌ی اون ۸۰ کالا تو یه لینک می‌رن.
  ۳) مطمئن می‌شه که صفحه‌ی asasi/index.html با اون لینک همه‌ی بنرها رو درست می‌سازه (هر بنر ۳۰ کالا)،
     و بعد یه آیتم تو دسته‌ی «دلار و طلا» / پوشه‌ی «کالاهای اساسی» اضافه می‌کنه.
  فقط ۸۰ کالای مهم (۴ بنر ۲۰ تایی) نشون داده می‌شه: از هر «نوع کالا» (برنج، مرغ، تخم‌مرغ، چای، ...) یه قلم با قیمت
  میانه‌ی چند سایت، و اگه به ۸۰ نرسید زیرنوع‌ها (فیله مرغ، ران مرغ، تخم‌مرغ ۶ و ۹ عددی، ...) اضافه می‌شن.

حالت clean (هر شب ۲۳:۳۰ تهران): آیتم‌هایی که خودِ ربات ساخته (شناسه‌شون با item_nabzeauto_ شروع می‌شه) پاک می‌شن.
"""
import base64
import json
import os
import re
import statistics
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone

# مهم: آدرس نباید به «.html» ختم بشه (برنامه برای لینک .html بخش بعد از # رو از دست می‌ده)؛ آدرس پوشه درسته.
VIEWER_BASE = os.environ.get("NABZE_VIEWER_URL", "https://asanarzz.github.io/ASANNET-APP/asasi/")
BOT_ID_PREFIX = "item_nabzeauto_"
SECTION_NAME = "کالاهای اساسی"
PER_PAGE = 20            # باید با PER_PAGE تو asasi/index.html یکی باشه (هر بنر ۲۰ کالا)
MAX_ITEMS = 80           # حداکثر کالای انتخاب‌شده = ۴ بنر ۲۰ تایی
MIN_ITEMS = 20           # اگه کمتر از این تعداد کالا پیدا بشه، آیتمی اضافه نمی‌شه
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


GROUP_ORDER = ["برنج و غلات", "مرغ", "تخم‌مرغ", "گوشت قرمز", "لبنیات", "روغن", "حبوبات", "چای و نوشیدنی", "سایر"]
SPECS = []


def S(tier, group, label, unit, inc, exc=None, urx=None, lo=0, hi=10 ** 12, also=None):
    SPECS.append(dict(tier=tier, group=group, label=label, unit=unit, inc=re.compile(inc),
                      exc=re.compile(exc) if exc else None, urx=re.compile(urx) if urx else None,
                      lo=lo, hi=hi, also=re.compile(also) if also else None))


KG = r"^(هر )?کیلوگرم$"
K1 = r"^1 ?کیلو"
K10 = r"^10 ?کیلو"
K5 = r"^(بسته )?5 ?کیلو"
# ── tier 1: نوع‌های اصلی ──
S(1, "برنج و غلات", "برنج ایرانی هاشمی", "۱۰ کیلوگرم", r"هاشمی", r"نیم ?دانه|ارگانیک", K10, 2_000_000, 7_500_000)
S(1, "برنج و غلات", "برنج خارجی (هندی/پاکستانی)", "هر کیلوگرم", r"برنج (خارجی|هندی|پاکستانی)", r"حد ", KG, 150_000, 600_000)
S(1, "مرغ", "مرغ گرم (کامل)", "هر کیلوگرم", r"مرغ (کامل|گرم)|گوشت مرغ",
  r"منجمد|زنده|بسته ?بندی|کیسه|عمده|درب|دره ?شهر|تعادلی|تنظیم|فهرست", KG, 250_000, 500_000)
S(1, "تخم‌مرغ", "تخم‌مرغ (شانه ۳۰ عددی)", "شانه", r"تخم ?مرغ", r"بلدرچین|کبک|زرد|پرطلایی|یک عدد",
  None, 450_000, 800_000, r"30|شانه")
S(1, "چای و نوشیدنی", "چای ایرانی", "۴۵۰ گرم", r"چای ایرانی", None, None, 300_000, 1_500_000)
S(1, "روغن", "روغن مایع (سرخ‌کردنی)", "۸۱۰ گرم", r"روغن", r"ذرت|تعویض|فیلتر", None, 250_000, 900_000, r"810")
S(1, "لبنیات", "شیر کم‌چرب بطری", "۹۴۶ گرم", r"^شیر", r"ماست|پنیر|شیرودی|خام|سبد|پرچرب", r"^(?!3 ?بطری)", 80_000, 250_000, r"946")
S(1, "لبنیات", "ماست کم‌چرب", "دبه ۲.۵ کیلوگرم", r"ماست", None, None, 150_000, 500_000, r"2\.5")
S(1, "لبنیات", "پنیر سفید یو اف", "۴۰۰ گرم", r"پنیر", r"کم ?نمک|نسبتا", None, 100_000, 400_000)
S(1, "حبوبات", "عدس", "۱ کیلوگرم", r"عدس", r"دال", K1, 120_000, 500_000)
S(1, "حبوبات", "لوبیا چیتی", "۱ کیلوگرم", r"لوبیا چیتی", None, K1, 200_000, 600_000)
S(1, "حبوبات", "نخود", "۱ کیلوگرم", r"نخود", r"سیاه|زودپز|سرآشپز", K1, 120_000, 500_000)
S(1, "حبوبات", "لپه", "۱ کیلوگرم", r"لپه", None, K1, 120_000, 500_000)
S(1, "حبوبات", "ماش", "۱ کیلوگرم", r"ماش", None, K1, 100_000, 700_000)
S(1, "گوشت قرمز", "ران گوسفندی", "هر کیلوگرم", r"ران گوسفندی", r"بدون|وارداتی|دو کیلو|3 کیلو|نیم|خورشتی|قلوه", None, 1_500_000, 3_500_000)
S(1, "گوشت قرمز", "ران گوساله", "هر کیلوگرم", r"ران گوساله", r"دو کیلو|آبگوشتی", None, 1_200_000, 3_500_000)
S(1, "گوشت قرمز", "گوشت چرخ‌کرده گوسفندی", "هر کیلوگرم", r"چرخ ?کرده.*گوسفند|گوسفند.*چرخ ?کرده", r"مخلوط", None, 800_000, 3_000_000)
# مواد پرمصرفی که شاید امروز تو داده نباشن؛ اگه بودن، خودبه‌خود میان
S(1, "برنج و غلات", "شکر", None, r"(?<![آ-ی])شکر(?![آ-ی])", r"شکلات|نی|ماست|چای|عسل|پنیر|بیسکوئیت", None, 30_000, 400_000)
S(1, "برنج و غلات", "قند", None, r"(?<![آ-ی])قند(?![آ-ی])", r"شکلات|عسل|تصفیه|خون", None, 30_000, 400_000)
S(1, "برنج و غلات", "آرد", None, r"(?<![آ-ی])آرد(?![آ-ی])", r"شنیتسل|بدون آرد|سوخاری", None, 20_000, 400_000)
S(1, "برنج و غلات", "ماکارونی", None, r"ماکارونی", None, None, 20_000, 300_000)
S(1, "برنج و غلات", "رب گوجه‌فرنگی", None, r"رب گوجه", None, None, 30_000, 500_000)
S(1, "برنج و غلات", "نمک", None, r"(?<![آ-ی])نمک(?![آ-ی])", r"کم ?نمک|پنیر|ماست", None, 5_000, 200_000)
# ── tier 2: زیرنوع‌ها (به ترتیب اولویت) ──
S(2, "تخم‌مرغ", "تخم‌مرغ بسته ۶ عددی", "۶ عدد", r"تخم ?مرغ", r"بلدرچین|کبک|فله", None, 100_000, 250_000, r"(?<![\d.])6 ?عد")
S(2, "تخم‌مرغ", "تخم‌مرغ بسته ۹ عددی", "۹ عدد", r"تخم ?مرغ", r"بلدرچین|کبک|فله", None, 150_000, 350_000, r"(?<![\d.])9 ?عد")
S(2, "مرغ", "فیله مرغ", "هر کیلوگرم", r"فیله مرغ", r"بسته 5|فهرست|گرم", KG, 400_000, 1_200_000)
S(2, "مرغ", "ران مرغ", "هر کیلوگرم", r"ران( کامل)? مرغ", r"بدون پوست|مغز|بسته", KG, 200_000, 700_000)
S(2, "مرغ", "سینه مرغ بدون استخوان", "هر کیلوگرم", r"سینه مرغ", r"با استخوان|بسته|شنیت|شنیسل|شش|بال|گردن|فهرست", KG, 450_000, 1_100_000, r"بدون استخوان")
S(2, "مرغ", "سینه مرغ با استخوان", "هر کیلوگرم", r"سینه مرغ", r"بدون استخوان|بسته|شنیت", KG, 350_000, 1_000_000, r"با استخوان")
S(2, "مرغ", "ران مرغ بدون پوست", "هر کیلوگرم", r"ران مرغ بدون پوست", r"بسته", KG, 300_000, 900_000)
S(2, "مرغ", "ساق مرغ بدون پوست", "هر کیلوگرم", r"ساق مرغ", None, KG, 300_000, 900_000)
S(2, "مرغ", "بال و بازوی مرغ", "هر کیلوگرم", r"بال و بازو", None, KG, 200_000, 700_000)
S(2, "مرغ", "مغز ران مرغ", "هر کیلوگرم", r"مغز ران مرغ", None, KG, 300_000, 900_000)
S(2, "مرغ", "جگر مرغ", "هر کیلوگرم", r"جگر مرغ", None, KG, 100_000, 500_000)
S(2, "مرغ", "دل مرغ", "هر کیلوگرم", r"دل مرغ", None, KG, 100_000, 500_000)
S(2, "مرغ", "سنگدان مرغ", "هر کیلوگرم", r"سنگدان", None, KG, 80_000, 500_000)
S(2, "مرغ", "شنیتسل مرغ", "هر کیلوگرم", r"شنیتسل", r"سینه|بسته", KG, 300_000, 1_200_000)
S(2, "مرغ", "مرغ منجمد", "هر کیلوگرم", r"مرغ منجمد", r"درب", KG, 150_000, 500_000)
S(2, "مرغ", "گوشت بوقلمون", "هر کیلوگرم", r"بوقلمون", None, KG, 250_000, 1_000_000)
S(2, "تخم‌مرغ", "تخم‌مرغ بسته ۱۵ عددی", "۱۵ عدد", r"تخم ?مرغ", r"بلدرچین|کبک|فله", None, 300_000, 520_000, r"(?<![\d.])15 ?عد")
S(2, "تخم‌مرغ", "تخم‌مرغ بسته ۲۰ عددی", "۲۰ عدد", r"تخم ?مرغ", r"بلدرچین|کبک|فله", None, 400_000, 700_000, r"(?<![\d.])20 ?عد")
S(2, "تخم‌مرغ", "تخم‌مرغ فله", "هر کیلوگرم", r"تخم ?مرغ فله", None, None, 150_000, 500_000, r"کیلو")
S(2, "گوشت قرمز", "فیله گوسفندی", "هر کیلوگرم", r"فیله گوسفندی", r"نیم|ران", None, 1_500_000, 4_500_000)
S(2, "گوشت قرمز", "راسته گوسفندی با استخوان", "هر کیلوگرم", r"راسته", r"بدون|مغزشده|شیشلیک|گوساله", None, 1_200_000, 3_500_000, r"گوسفند")
S(2, "گوشت قرمز", "سردست گوسفندی", "هر کیلوگرم", r"سردست گوسفندی", r"بدون|نیم", None, 1_200_000, 3_500_000)
S(2, "گوشت قرمز", "ماهیچه گوسفندی", "هر کیلوگرم", r"ماهیچه گوسفندی", r"گل", None, 1_500_000, 3_500_000)
S(2, "گوشت قرمز", "گردن گوسفندی", "هر کیلوگرم", r"گردن گوسفندی", None, None, 1_000_000, 3_500_000)
S(2, "گوشت قرمز", "خورشتی گوسفندی", "هر کیلوگرم", r"خورشتی گوسفندی|گوشت خورشتی گوسفندی", r"ران", None, 1_000_000, 3_500_000)
S(2, "گوشت قرمز", "گوشت آبگوشتی گوسفندی", "هر کیلوگرم", r"آبگوشتی گوسفندی", None, None, 1_000_000, 3_000_000)
S(2, "گوشت قرمز", "قلوه‌گاه گوسفندی", "هر کیلوگرم", r"قلوه ?گاه گوسفندی", r"بدون|منجمد|ران", None, 800_000, 3_000_000)
S(2, "گوشت قرمز", "دنبه گوسفندی", "هر کیلوگرم", r"دنبه", None, None, 800_000, 3_000_000)
S(2, "گوشت قرمز", "گوشت چرخ‌کرده گوساله", "هر کیلوگرم", r"چرخ ?کرده", r"گوسفند|مخلوط", None, 1_000_000, 3_000_000, r"گوساله")
S(2, "گوشت قرمز", "خورشتی گوساله", "هر کیلوگرم", r"خورشتی گوساله", None, None, 1_000_000, 4_000_000)
S(2, "گوشت قرمز", "راسته گوساله", "هر کیلوگرم", r"راسته گوساله", None, None, 1_200_000, 3_500_000)
S(2, "گوشت قرمز", "سردست گوساله", "هر کیلوگرم", r"سردست گوساله", None, None, 1_200_000, 3_500_000)
S(2, "گوشت قرمز", "ماهیچه گوساله", "هر کیلوگرم", r"ماهیچه.*گوساله", None, None, 1_200_000, 3_500_000)
S(2, "گوشت قرمز", "گوشت منجمد گوساله", "هر کیلوگرم", r"منجمد گوساله", None, None, 300_000, 1_500_000)
S(2, "گوشت قرمز", "گوسفند زنده", "هر کیلوگرم (وزن زنده)", r"^گوسفند( زنده)?$", None, None, 300_000, 1_200_000)
S(2, "گوشت قرمز", "گوساله زنده", "هر کیلوگرم (وزن زنده)", r"^گوساله( زنده)?$", None, None, 300_000, 1_200_000)
S(2, "برنج و غلات", "برنج طارم", "۱۰ کیلوگرم", r"طارم", r"هاشمی|ارگانیک|نیم ?دانه", K10, 1_800_000, 7_500_000)
S(2, "برنج و غلات", "برنج عنبربو", "۱۰ کیلوگرم", r"عنبربو", None, K10, 1_800_000, 6_000_000)
S(2, "برنج و غلات", "برنج کامفیروزی", "۱۰ کیلوگرم", r"کامفیروزی", None, K10, 1_800_000, 6_000_000)
S(2, "برنج و غلات", "برنج هندی", "۱۰ کیلوگرم", r"هندی", None, K10, 1_800_000, 6_000_000)
S(2, "برنج و غلات", "برنج هاشمی", "۵ کیلوگرم", r"هاشمی", r"نیم ?دانه|ارگانیک", r"^5 ?کیلو", 1_200_000, 4_500_000)
S(2, "برنج و غلات", "برنج ایرانی (هر کیلو)", "هر کیلوگرم", r"^برنج (ایرانی|طارم محلی)", None, KG, 250_000, 900_000)
S(2, "لبنیات", "شیر کم‌چرب بطری", "۱ لیتر", r"^شیر", r"ماست|پنیر|شیرودی|خام|سبد|پرچرب|کیسه|نایلونی", None, 60_000, 200_000, r"یک لیتر")
S(2, "لبنیات", "شیر کم‌چرب کیسه‌ای", "۹۰۰ گرم", r"^شیر", r"ماست|پنیر|شیرودی|خام|سبد|پرچرب|بطری", None, 50_000, 200_000, r"900")
S(2, "لبنیات", "ماست کم‌چرب", "دبه ۲ کیلوگرم", r"ماست", None, None, 120_000, 500_000, r"(?<![\d.])2 ?کیلو")
S(2, "لبنیات", "پنیر کم‌نمک", "۴۰۰ گرم", r"پنیر کم ?نمک", None, None, 100_000, 450_000)
S(2, "لبنیات", "کره", "۵۰ گرم", r"^کره", r"خامه|اضافی", None, 30_000, 250_000)
S(2, "لبنیات", "خامه", "۲۰۰ گرم", r"^خامه", None, None, 60_000, 300_000)
S(2, "روغن", "روغن آفتابگردان لادن", "۱.۸ لیتر", r"روغن", r"ذرت|تعویض|فیلتر", None, 300_000, 1_200_000, r"1\.8")
S(2, "روغن", "روغن نیمه‌جامد", "۵ کیلوگرم", r"روغن", r"ذرت|تعویض|فیلتر", None, 1_000_000, 4_000_000, r"غنچه|نیمه ?جامد")
S(2, "روغن", "روغن ذرت", "۱.۵ لیتر", r"روغن ذرت", None, None, 500_000, 3_000_000)
S(2, "حبوبات", "لوبیا قرمز", "۱ کیلوگرم", r"لوبیا قرمز", None, K1, 150_000, 600_000)
S(2, "حبوبات", "لوبیا سفید", "۱ کیلوگرم", r"لوبیا سفید", None, K1, 150_000, 600_000)
S(2, "حبوبات", "لوبیا چشم‌بلبلی", "۱ کیلوگرم", r"چشم ?بلبلی", None, K1, 150_000, 600_000)
S(2, "حبوبات", "نخود", "۵۰۰ گرم", r"نخود", r"سیاه|سرآشپز", r"^500", 80_000, 400_000)
S(2, "حبوبات", "عدس", "۹۰۰ گرم", r"عدس", None, r"^900", 150_000, 600_000)
S(2, "چای و نوشیدنی", "چای خارجی", "۵۰۰ گرم", r"چای خارجی", None, None, 250_000, 1_500_000)
S(2, "مرغ", "فیله مرغ", "بسته ۵ کیلوگرم", r"فیله مرغ", r"فهرست", K5, 1_500_000, 6_000_000)
S(2, "مرغ", "ران مرغ بدون پوست", "بسته ۵ کیلوگرم", r"ران مرغ بدون پوست", None, K5, 800_000, 3_000_000)
S(2, "گوشت قرمز", "گوشت چرخ‌کرده مخلوط گوساله و گوسفند", "هر کیلوگرم", r"چرخ ?کرده", r"گوسفندی خالص|ممتاز", None, 1_000_000, 3_000_000, r"مخلوط")
S(2, "مرغ", "کتف و بال مرغ", "هر کیلوگرم", r"کتف و بال", None, KG, 150_000, 700_000)
S(2, "مرغ", "پای مرغ", "هر کیلوگرم", r"پای مرغ", None, KG, 80_000, 500_000)
S(2, "مرغ", "گردن مرغ بدون پوست", "هر کیلوگرم", r"گردن مرغ", None, KG, 150_000, 700_000)
S(2, "مرغ", "مرغ زنده", "هر کیلوگرم (وزن زنده)", r"مرغ زنده", None, KG, 100_000, 500_000)
S(2, "حبوبات", "لوبیا چیتی", "۹۰۰ گرم", r"لوبیا چیتی", None, r"^900", 250_000, 900_000)
S(2, "حبوبات", "لپه", "۹۰۰ گرم", r"لپه", None, r"^900", 200_000, 900_000)
S(2, "حبوبات", "ماش", "۹۰۰ گرم", r"ماش", None, r"^900", 100_000, 900_000)
S(2, "گوشت قرمز", "مغز گوسفندی", "هر عدد", r"مغز گوسفند", r"ران", None, 200_000, 1_500_000)


def ntext(s):
    t = norm(s).replace("‌", " ").replace("ي", "ی").replace("ك", "ک")
    return re.sub(r"\s+", " ", t).strip()


def _matches(spec, x):
    name = ntext(x.get("name", ""))
    unit = ntext(x.get("unit", ""))
    if not spec["inc"].search(name):
        return False
    if spec["exc"] and spec["exc"].search(name):
        return False
    if spec["urx"] and not spec["urx"].search(unit):
        return False
    if spec["also"] and not spec["also"].search(name + " " + unit):
        return False
    try:
        price = float(x["max"])
    except (KeyError, TypeError, ValueError):
        return False
    return spec["lo"] <= price <= spec["hi"]


def select_items(items, limit=MAX_ITEMS):
    """برای هر «نوع کالا» یه قلم می‌سازه (قیمت = میانه‌ی قیمت‌های معتبر) و حداکثر `limit` قلم برمی‌گردونه."""
    chosen = []
    for spec in SPECS:
        found = [x for x in items if _matches(spec, x)]
        if not found:
            continue
        prices = sorted(float(x["max"]) for x in found)
        price = statistics.median_low(prices)
        newest = max(item_date(x) for x in found)
        unit = spec["unit"]
        if unit is None:
            units = [clean_unit(x.get("unit", "")) for x in found if clean_unit(x.get("unit", ""))]
            unit = max(set(units), key=units.count) if units else ""
        chosen.append({"tier": spec["tier"], "group": spec["group"], "label": spec["label"], "unit": unit,
                       "min": price, "max": price, "reportDate": newest, "cat": spec["group"],
                       "name": spec["label"], "n": len(found)})
    chosen.sort(key=lambda c: c["tier"])          # نوع‌های اصلی اول (sort پایداره؛ ترتیب لیست حفظ می‌شه)
    chosen = chosen[:limit]
    order = {g: i for i, g in enumerate(GROUP_ORDER)}
    chosen.sort(key=lambda c: order.get(c["group"], len(GROUP_ORDER)))   # برای نمایش: گروه‌به‌گروه
    return chosen


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
                "صفحه‌ی نمایش بنر (%s) درست کار نکرد: %s — اگه همین الان فایل asasi/index.html رو آپلود "
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
    raw = merge_items(load_scraped())
    print("کالاهای معتبر بعد از ادغام (قبل از انتخاب): %d" % len(raw))
    items = select_items(raw)
    print("کالاهای انتخاب‌شده: %d (نوع اصلی: %d، زیرنوع: %d)" % (
        len(items), sum(1 for x in items if x["tier"] == 1), sum(1 for x in items if x["tier"] == 2)))
    for x in items:
        print("  - %s [%s] = %d (از %d قیمت)" % (x["label"], x["unit"], x["max"], x["n"]))
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
