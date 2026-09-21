// linkfinder.js — تب «لینک‌یاب»
// نحوهٔ استفاده: این فایل رو توی پوشهٔ admin بذار و این خط رو قبل از </body> توی index.html اضافه کن:
// <script src="linkfinder.js"></script>

(function() {
  const lfProxyStoreKey = 'kafinet_lf_proxy';
  let lfLastResults = [];

  function $(id) { return document.getElementById(id); }

  function lfEscapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }

  function lfLoadProxy() {
    try {
      const v = localStorage.getItem(lfProxyStoreKey);
      if (v && $('lf-proxy')) $('lf-proxy').value = v;
    } catch (e) {}
  }

  function lfSaveProxy() {
    try {
      if ($('lf-save-proxy').checked) localStorage.setItem(lfProxyStoreKey, $('lf-proxy').value.trim());
      else localStorage.removeItem(lfProxyStoreKey);
    } catch (e) {}
  }

  async function lfFetchHtml(url, proxy) {
    let fetchUrl = url;
    if (proxy) fetchUrl = proxy + encodeURIComponent(url);
    const res = await fetch(fetchUrl, { method: 'GET', mode: 'cors' });
    if (!res.ok) throw new Error('خطا در دریافت صفحه (' + res.status + ')');
    return res.text();
  }

  function lfExtractLinks(htmlText, pageUrl) {
    const base = new URL(pageUrl);
    const parser = new DOMParser();
    const doc = parser.parseFromString(htmlText, 'text/html');
    const anchors = Array.from(doc.querySelectorAll('a[href]'));
    const seen = new Set();
    const results = [];
    anchors.forEach((a) => {
      let href = a.getAttribute('href').trim();
      if (!href) return;
      let absolute;
      try { absolute = new URL(href, base).href; } catch (e) { return; }
      if (seen.has(absolute)) return;
      const extMatch = absolute.match(/\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|mp3|m4a|wav|ogg|aac|flac|zip|rar|7z|tar|gz|pdf|apk|exe|dmg|iso)([#?].*)?$/i);
      if (!extMatch) return;
      seen.add(absolute);
      const label = (a.textContent || '').trim().slice(0, 60) || extMatch[1].toUpperCase();
      results.push({ url: absolute, label });
    });
    return results;
  }

  function lfRenderResults(results) {
    const box = $('lf-results');
    const actions = $('lf-actions');
    if (!results.length) {
      box.innerHTML = '<div class="empty-msg">هیچ لینک دانلودی پیدا نشد</div>';
      actions.style.display = 'none';
      return;
    }
    box.innerHTML = results.map((r, i) => `
      <div class="item" style="align-items:flex-start;">
        <label style="display:flex; align-items:center; gap:8px; cursor:pointer; margin:0; flex:1;">
          <input type="checkbox" class="lf-check" data-i="${i}" style="width:auto; flex-shrink:0;" checked>
          <div style="min-width:0;">
            <div class="t" style="font-weight:600;">${lfEscapeHtml(r.label)}</div>
            <div class="d" style="font-size:12px; direction:ltr; text-align:right;">${lfEscapeHtml(r.url)}</div>
          </div>
        </label>
      </div>
    `).join('');
    actions.style.display = 'block';
  }

  async function lfRun() {
    const url = $('lf-url').value.trim();
    if (!url) { alert('آدرس صفحه رو وارد کن'); return; }
    const proxy = $('lf-proxy').value.trim();
    const status = $('lf-status');
    const btn = $('lf-run');
    btn.disabled = true;
    status.textContent = 'در حال دریافت صفحه…';
    try {
      const htmlText = await lfFetchHtml(url, proxy);
      status.textContent = 'در حال استخراج لینک‌ها…';
      const results = lfExtractLinks(htmlText, url);
      lfLastResults = results;
      lfRenderResults(results);
      status.textContent = results.length ? (results.length + ' لینک پیدا شد') : 'هیچ لینک دانلودی پیدا نشد';
      lfSaveProxy();
    } catch (e) {
      status.textContent = '';
      alert(e.message);
    } finally {
      btn.disabled = false;
    }
  }

  function lfAddToForm(all) {
    const checks = document.querySelectorAll('.lf-check');
    let added = 0;
    checks.forEach((cb) => {
      if (all || cb.checked) {
        const i = parseInt(cb.getAttribute('data-i'), 10);
        const r = lfLastResults[i];
        if (r && typeof addLinkRow === 'function') {
          addLinkRow(r.label, r.url);
          added++;
        }
      }
    });
    if (added) {
      alert(added + ' لینک به فرم اضافه شد');
      // برگشت به تب محتوا
      const contentTab = document.getElementById('main-tab-content');
      if (contentTab && typeof switchMainTab === 'function') {
        switchMainTab('content');
        $('section-content').scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    } else {
      alert('هیچ لینکی انتخاب نشده');
    }
  }

  function init() {
    // اضافه کردن تب لینک‌یاب به main-tabs
    const mainTabs = document.querySelector('.main-tabs');
    if (!mainTabs || $('main-tab-linkfinder')) return;

    const lfTab = document.createElement('div');
    lfTab.className = 'main-tab';
    lfTab.id = 'main-tab-linkfinder';
    lfTab.textContent = 'لینک‌یاب';
    mainTabs.appendChild(lfTab);

    // اضافه کردن بخش لینک‌یاب
    const appArea = $('app-area');
    if (!appArea) return;

    const lfSection = document.createElement('div');
    lfSection.id = 'section-linkfinder';
    lfSection.className = 'hidden';
    lfSection.innerHTML = `
      <div class="card">
        <h2>لینک‌یاب — استخراج خودکار لینک‌ها</h2>
        <p class="muted">آدرس صفحه‌ای که لینک‌های دانلود توش هست رو بذار، ما خودمون لینک‌ها رو پیدا می‌کنیم.</p>

        <label>آدرس صفحه</label>
        <input type="url" id="lf-url" placeholder="https://example.com/page-with-links">

        <label>پروکسی (اختیاری — اگه صفحه لود نمی‌شه)</label>
        <input type="text" id="lf-proxy" placeholder="https://api.allorigins.win/raw?url=">
        <p class="muted">
          <label style="display:inline; margin:0;"><input type="checkbox" id="lf-save-proxy" style="width:auto; vertical-align:middle;"> این پروکسی رو برای دفعه بعد یادت بمونه</label>
        </p>

        <button type="button" class="btn-primary btn-full" id="lf-run">🔍 جستجو و استخراج لینک‌ها</button>
        <div id="lf-status" class="muted" style="margin-top:10px;"></div>

        <div id="lf-results" style="margin-top:14px;"></div>

        <div id="lf-actions" style="margin-top:14px; display:none;">
          <button type="button" class="btn-primary btn-full" id="lf-add-selected">➕ افزودن لینک‌های انتخاب‌شده به فرم</button>
          <button type="button" class="btn-outline btn-full" id="lf-add-all" style="margin-top:8px;">➕ افزودن همه‌ی لینک‌ها به فرم</button>
        </div>
      </div>
    `;
    appArea.appendChild(lfSection);

    // رویداد کلیک تب
    lfTab.addEventListener('click', () => {
      document.querySelectorAll('.main-tab').forEach(t => t.classList.remove('active'));
      lfTab.classList.add('active');
      ['section-content','section-docs','section-support','section-stats','section-linkfinder'].forEach(id => {
        const el = $(id);
        if (el) el.classList.toggle('hidden', id !== 'section-linkfinder');
      });
    });

    // رویدادهای دکمه‌ها
    $('lf-run').addEventListener('click', lfRun);
    $('lf-add-selected').addEventListener('click', () => lfAddToForm(false));
    $('lf-add-all').addEventListener('click', () => lfAddToForm(true));

    lfLoadProxy();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
