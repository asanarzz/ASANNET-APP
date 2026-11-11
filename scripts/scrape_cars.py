#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""دریافت خودکار قیمت روز خودرو از چند سایت خبری و ساخت data/scraped.json (فقط کتابخانه‌های استاندارد پایتون)."""
import json, re, os, sys, datetime as dt, html as htmllib, urllib.request, urllib.parse, urllib.robotparser
from html.parser import HTMLParser
from xml.etree import ElementTree as ET
from email.utils import parsedate_to_datetime

OUT = os.environ.get('KHODRO_OUT') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'khodro_scraped.json')
UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
      'Accept-Language': 'fa,en;q=0.8'}
QUERIES = ['قیمت خودرو امروز', 'قیمت خودروهای ایران خودرو امروز', 'قیمت خودروهای سایپا امروز', 'قیمت خودرو مونتاژی و وارداتی امروز']
MAX_PAGES, MAX_AGE_DAYS, MIN_ITEMS_PAGE, MIN_ITEMS_TOTAL = 30, 3, 5, 10
MONTHS = ['فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور', 'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند']
CATS = [('وارداتی', r'سوزوکی|ویتارا|جیمنی|رنو|ولوو|آئودی|آدی|پورشه|مرسدس|بنز|بی‌ام‌و|BMW|لکسوس|تانک|میتسوبیشی|اسکودا|بی‌وای‌دی|BYD|امجی|MG|ام‌جی|تویوتا|هیوندای|(?<![آ-ی])کیا(?![آ-ی])|نیسان|هوندا|مزدا|فولکس|اوشان|Oshan|فرانتلندر|کرولا|لندکروز|پرادو|رافور|هایلندر|سانتافه|توسان|سوناتا|النترا'),
        ('کرمان موتور', r'(?<![آ-ی])جک(?![آ-ی])|کی‌ام‌سی|KMC|(?<![آ-ی])بک(?![آ-ی])'),
        ('ایران‌خودرو', r'پژو|(?<![آ-ی])دنا|سورن|(?<![آ-ی])رانا|(?<![آ-ی])تارا|آریسان|ری[ \u200c]?را|هایما|فوتون|سمند'),
        ('سایپا', r'سهند|ساینا|کوییک|اطلس|شاهین|سایپا|زامیاد|کارون|چانگان|سیتروئن|پراید|تیبا'),
        ('مدیران خودرو', r'X22|X33|X55|تیگو|آریزو|فونیکس'),
        ('بهمن موتور', r'فیدلیتی|دیگنیتی|ریسپکت|رسپکت|(?<![آ-ی])کارا(?![آ-ی])|کاپرا|اینوی|H9')]
STOP = {'و', 'با', 'ای', 'مدل', 'تیپ'}


def digits(s):
    s = str(s).translate(str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩', '01234567890123456789'))
    return s.replace('ي', 'ی').replace('ك', 'ک').replace('\xa0', ' ')


def cat(n):
    return next((c for c, rx in CATS if re.search(rx, n)), 'سایر')


def j2g(jy, jm, jd):  # تبدیل شمسی به میلادی
    jy += 1595
    days = -355668 + 365 * jy + (jy // 33) * 8 + ((jy % 33) + 3) // 4 + jd + ((jm - 1) * 31 if jm < 7 else (jm - 7) * 30 + 186)
    gy = 400 * (days // 146097); days %= 146097
    if days > 36524:
        days -= 1; gy += 100 * (days // 36524); days %= 36524
        if days >= 365: days += 1
    gy += 4 * (days // 1461); days %= 1461
    if days > 365: gy += (days - 1) // 365; days = (days - 1) % 365
    gd = days + 1
    ml = [0, 31, 29 if (gy % 4 == 0 and gy % 100 != 0) or gy % 400 == 0 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    gm = 0
    while gm < 13 and gd > ml[gm]:
        gd -= ml[gm]; gm += 1
    return dt.date(gy, gm, gd)


def g2j(gy, gm, gd):
    gdm = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
    gy2 = gy + 1 if gm > 2 else gy
    days = 355666 + 365 * gy + (gy2 + 3) // 4 - (gy2 + 99) // 100 + (gy2 + 399) // 400 + gd + gdm[gm - 1]
    jy = -1595 + 33 * (days // 12053); days %= 12053
    jy += 4 * (days // 1461); days %= 1461
    if days > 365: jy += (days - 1) // 365; days = (days - 1) % 365
    return (jy, 1 + days // 31, 1 + days % 31) if days < 186 else (jy, 7 + (days - 186) // 30, 1 + (days - 186) % 30)


def find_date(text):
    m = re.search(r'(\d{1,2})\s*(%s)(?:[\s\u200c]*ماه)?\s*(\d{4})' % '|'.join(MONTHS), digits(text))
    if not m: return None
    try: return j2g(int(m.group(3)), MONTHS.index(m.group(2)) + 1, int(m.group(1)))
    except Exception: return None


PR = re.compile(r'(\d+(?:\.\d+)?)\s*میلیارد(?:\s*و\s*(\d+(?:\.\d+)?)\s*میلیون)?|(\d+(?:\.\d+)?)\s*میلیون|(?<![\d.])(\d{9,11})(?![\d.])')


def price(cell):
    m = PR.search(re.sub(r'[,٬،]', '', digits(cell)))
    if not m: return None
    if m.group(1): v = float(m.group(1)) * 1e9 + float(m.group(2) or 0) * 1e6
    elif m.group(3): v = float(m.group(3)) * 1e6
    else: v = float(m.group(4))
    v = round(v)
    return v if 2e8 <= v <= 1e11 else None


class TP(HTMLParser):
    def __init__(s):
        super().__init__(); s.rows = []; s.row = None; s.cell = None
    def handle_starttag(s, t, a):
        if t == 'tr': s.row = []
        elif t in ('td', 'th') and s.row is not None: s.cell = []
        elif t == 'br' and s.cell is not None: s.cell.append(' ')
    def handle_endtag(s, t):
        if t in ('td', 'th') and s.cell is not None and s.row is not None:
            s.row.append(' '.join(''.join(s.cell).split())); s.cell = None
        elif t == 'tr' and s.row is not None:
            if s.row: s.rows.append(s.row)
            s.row = None
    def handle_data(s, d):
        if s.cell is not None: s.cell.append(d)


def clean_name(n):
    n = digits(n)
    n = re.sub(r'\s*[,،]?\s*\b(1[34]\d\d|20\d\d)\b\s*$', '', n.strip())
    return re.sub(r'\s+', ' ', n).strip(' -–:|')


def parse_rows(rows):
    out, mk, ag = [], None, None
    for r in rows:
        if not any(price(c) for c in r) and any(k in ' '.join(r) for k in ('بازار', 'کارخانه', 'نمایندگی', 'قیمت')):
            mk = ag = None
            for i, c in enumerate(r):
                if 'کارخانه' in c or 'نمایندگی' in c: ag = i
                elif any(k in c for k in ('بازار', 'قیمت', 'روز')) and mk is None: mk = i
            continue
        name, prices = None, []
        for i, c in enumerate(r):
            p = price(c)
            if p: prices.append((i, p))
            elif name is None and re.search(r'[آ-ی]|[A-Za-z]', c) and not re.search(r'تغییر|بازار|کارخانه|نمایندگی', c): name = c
        name = clean_name(name) if name else None
        if not name or len(name) < 3 or not prices: continue
        market, agency = None, 0
        if mk is not None:
            market = next((p for i, p in prices if i == mk), None)
            agency = next((p for i, p in prices if i == ag), 0) if ag is not None else 0
            if market is None: continue  # «ناموجود» یا بدون قیمت بازار
        else:
            market = prices[0][1]
            if len(prices) > 1 and 0.3 <= prices[1][1] / market <= 2: agency = prices[1][1]
        out.append({'name': name, 'price': market, 'agency': agency, 'cat': cat(name)})
    return out


PRS = r'\d+(?:\.\d+)?\s*میلیارد(?:\s*و\s*\d+(?:\.\d+)?\s*میلیون)?|\d+(?:\.\d+)?\s*میلیون|(?<![\d.])\d{9,11}(?![\d.])'
RUN = re.compile(r'(?:(?:%s)\s*(?:تومان)?[\s·|]*){1,2}' % PRS)
JUNK = re.compile(r'ناموجود|توقف\s*(?:فروش|تولید)|به\s*زودی|اعلام\s*نشده|\(?[-+]?\d+(?:\.\d+)?[%٪]\)?\s*[-+]?\d*')
BAD = {'به', 'که', 'است', 'شد', 'رسید', 'را', 'در', 'از', 'برای', 'هم', 'این', 'آن', 'قیمت', 'امروز', 'تومان', 'بازار', 'نمایندگی', 'کارخانه'}


def html_text(h):
    h = re.sub(r'(?is)<(script|style|noscript)\b.*?</\1>', ' ', h)
    h = re.sub(r'(?i)</t[dh]\s*>', ' · ', h)
    h = re.sub(r'(?i)</(tr|li|p|div|h[1-6]|ul|ol|table|section|article)\s*>|<br\s*/?>', '\n', h)
    return htmllib.unescape(re.sub(r'<[^>]+>', ' ', h))


def parse_text(h):
    out = []
    for line in html_text(h).split('\n'):
        s = re.sub(r'\s+', ' ', re.sub(r'[,٬،]', '', digits(line))).strip()
        if len(s) < 8: continue
        pos = 0
        for m in RUN.finditer(s):
            seg, pos = s[pos:m.start()], m.end()
            vals = [price(t) for t in re.findall(PRS, m.group(0))]
            if not vals or not vals[0]: continue
            if re.search(r'(?:ناموجود|اعلام\s*نشده)\s*[·|\s]*$', seg): continue  # قیمت بازار این ردیف ندارد
            pieces = [x.strip(' ·|-–:') for x in JUNK.split(seg)]
            name = clean_name(next((x for x in reversed(pieces) if x), ''))
            w = name.split()
            if len(name) < 3 or len(w) > 8 or w[0] in BAD or not re.search(r'[آ-ی]', name): continue
            ag = vals[1] if len(vals) > 1 and vals[1] and 0.3 <= vals[1] / vals[0] <= 2 else 0
            out.append({'name': name, 'price': vals[0], 'agency': ag, 'cat': cat(name)})
    return out


_ROB = {}


def allowed(url):  # احترام به robots.txt؛ اگر در دسترس نبود، آزاد فرض می‌شود
    u = urllib.parse.urlparse(url); host = u.scheme + '://' + u.netloc
    if host not in _ROB:
        r = urllib.robotparser.RobotFileParser()
        try:
            r.parse(get(host + '/robots.txt').splitlines())
        except Exception:
            r = None
        _ROB[host] = r
    r = _ROB[host]
    return True if r is None else r.can_fetch('*', url)


def tokens(n):
    return {t for t in re.findall(r'[آ-یA-Za-z0-9]+', n.replace('\u200c', ' ').lower()) if t not in STOP}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode('utf-8', 'replace')


def queries(today):
    qs = list(QUERIES)
    for back in (0, 1):
        jy, jm, jd = g2j(*(today - dt.timedelta(days=back)).timetuple()[:3])
        ds = str.translate('%d %s %d' % (jd, MONTHS[jm - 1], jy), str.maketrans('0123456789', '۰۱۲۳۴۵۶۷۸۹'))
        qs += ['قیمت محصولات ایران خودرو و سایپا امروز ' + ds, 'قیمت خودرو امروز ' + ds, 'قیمت خودروهای ایران خودرو ' + ds, 'قیمت خودروهای سایپا ' + ds, 'قیمت خودرو وارداتی ' + ds]
    return qs


def candidates(today):
    seen, res = set(), []
    for q in queries(today):
        try:
            xml = get('https://www.bing.com/news/search?q=%s&format=rss&setlang=fa&cc=IR' % urllib.parse.quote(q))
            for it in ET.fromstring(xml).iter('item'):
                title, link = it.findtext('title') or '', it.findtext('link') or ''
                url = urllib.parse.parse_qs(urllib.parse.urlparse(link).query).get('url', [link])[0]
                d = find_date(title)  # فقط تاریخ شمسی با سال داخل عنوان معتبر است
                if url not in seen and 'خودرو' in title: seen.add(url); res.append((title, url, d))
        except Exception as e:
            print('خطا در جست‌وجوی خبر:', q, e, file=sys.stderr)
    return res


def tag_text(src, tag):
    m = re.search(r'(?is)<%s\b[^>]*>(.*?)</%s>' % (tag, tag), src)
    return re.sub(r'<[^>]+>', ' ', htmllib.unescape(m.group(1))) if m else ''


def page_date(src):  # تاریخ عنوان یا تیتر اصلی خود صفحه
    return find_date(tag_text(src, 'h1')) or find_date(tag_text(src, 'title'))


def collect(today):
    pages, log = [], []
    for title, url, d in candidates(today):
        if len(pages) + len(log) >= MAX_PAGES: break
        if d is None or not (today - dt.timedelta(days=MAX_AGE_DAYS) <= d <= today + dt.timedelta(days=1)): continue
        host = urllib.parse.urlparse(url).netloc.replace('www.', '')
        try:
            if not allowed(url):
                log.append({'source': host, 'url': url, 'status': 'robots', 'count': 0, 'date': str(d), 'error': 'robots.txt اجازه نمی‌دهد'}); continue
            src = get(url)
            pdt = page_date(src)
            if pdt and not (today - dt.timedelta(days=MAX_AGE_DAYS) <= pdt <= today + dt.timedelta(days=1)):
                log.append({'source': host, 'url': url, 'status': 'error', 'count': 0, 'date': str(pdt), 'title': title[:70], 'error': 'تاریخ داخل صفحه قدیمی است'}); continue
            d = pdt or d
            p = TP(); p.feed(src); items = parse_rows(p.rows)
            if len(items) < MIN_ITEMS_PAGE: items = parse_text(src)
            ok = len(items) >= MIN_ITEMS_PAGE
            log.append({'source': host, 'url': url, 'status': 'ok' if ok else 'error', 'count': len(items), 'date': str(d), 'title': title[:70], **({} if ok else {'error': 'جدول قیمت کافی پیدا نشد'})})
            if ok: pages.append({'host': host, 'url': url, 'date': d, 'items': items})
        except Exception as e:
            log.append({'source': host, 'url': url, 'status': 'error', 'count': 0, 'date': str(d), 'error': str(e)[:120]})
    return pages, log


def merge(pages):
    if not pages: return []
    newest = max(p['date'] for p in pages)
    use = [p for p in pages if p['date'] >= newest - dt.timedelta(days=1)]
    res, toks = [], []
    for c in sorted({i['cat'] for p in use for i in p['items']}):
        # صفحه‌ها را برای این گروه رتبه‌بندی کن: تازه‌تر، دارای قیمت نمایندگی بیشتر، تعداد بیشتر
        ranked = sorted(use, key=lambda p: (p['date'] != newest,
                                            -sum(1 for i in p['items'] if i['cat'] == c and i['agency']),
                                            -sum(1 for i in p['items'] if i['cat'] == c)))
        n = 0
        for k, p in enumerate(ranked):
            if k and n >= 10: break  # صفحه اصلی این گروه کافی بود
            for i in p['items']:
                if i['cat'] != c: continue
                t = tokens(i['name'])
                if not t or any(len(t & o) / len(t | o) >= 0.85 for o in toks): continue
                toks.append(t); n += 1
                res.append({**i, 'source': p['host'], 'url': p['url'], 'reportDate': str(p['date']), 'kind': 'scraped'})
    return res


def main():
    today = dt.datetime.now(dt.timezone.utc).date()
    pages, log = collect(today)
    items = merge(pages)
    old = {}
    if os.path.exists(OUT):
        try: old = json.load(open(OUT, encoding='utf-8'))
        except Exception: pass
    if len(items) < MIN_ITEMS_TOTAL:
        print('قیمت کافی پیدا نشد؛ داده قبلی نگه داشته شد.', file=sys.stderr)
        items = old.get('items', [])
        updated = old.get('updated')
        log.append({'source': 'کل اجرا', 'status': 'error', 'count': 0, 'error': 'داده جدید کافی نبود، داده قبلی مانده است'})
    else:
        updated = dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')
    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    json.dump({'updated': updated, 'items': items, 'log': log}, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('تعداد خودرو:', len(items), '| صفحه موفق:', len(pages), '| کل لاگ:', len(log))


if __name__ == '__main__':
    main()
