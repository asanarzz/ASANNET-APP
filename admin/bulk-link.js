// bulk-link.js — دکمهٔ «+ افزودن چند لینک با هم»
// نحوهٔ استفاده: این فایل رو توی پوشهٔ admin بذار و این خط رو قبل از </body> توی index.html اضافه کن:
// <script src="bulk-link.js"></script>

(function() {
  // وقتی DOM آماده شد اجرا کن
  function init() {
    const btnAddLink = document.getElementById('btn-add-link');
    const linksBox = document.getElementById('links-box');
    if (!btnAddLink || !linksBox) return;

    // درست کردن دکمهٔ تغییر وضعیت
    const toggleBtn = document.createElement('button');
    toggleBtn.type = 'button';
    toggleBtn.className = 'btn-outline btn-full';
    toggleBtn.id = 'btn-toggle-bulk-link';
    toggleBtn.style.marginTop = '8px';
    toggleBtn.style.color = '#2563EB';
    toggleBtn.style.borderColor = '#2563EB';
    toggleBtn.textContent = '+ افزودن چند لینک با هم';
    btnAddLink.parentNode.insertBefore(toggleBtn, btnAddLink.nextSibling);

    // درست کردن باکس چندلینکی
    const bulkBox = document.createElement('div');
    bulkBox.id = 'bulk-link-box';
    bulkBox.className = 'hidden';
    bulkBox.style.marginTop = '10px';
    bulkBox.innerHTML = `
      <textarea id="in-url-bulk" placeholder="هر خط یه لینک
https://... 720p
https://... 1080p
..." style="min-height:120px; width:100%; padding:10px 12px; border:1px solid var(--border); border-radius:10px; font-family:inherit; font-size:14px;"></textarea>
      <p class="muted" style="font-size:13px; color:var(--muted); margin:5px 0 0;">هر خط: آدرس + فاصله + برچسب (مثلاً 720p یا قسمت 1). اگه برچسب نباشه، خودش می‌سازه.</p>
      <button type="button" class="btn-primary btn-full" id="btn-parse-bulk" style="margin-top:8px;">✓ تبدیل به ردیف‌ها</button>
    `;
    toggleBtn.parentNode.insertBefore(bulkBox, toggleBtn.nextSibling);

    // ریداد کلیک دکمهٔ تغییر وضعیت
    toggleBtn.addEventListener('click', () => {
      bulkBox.classList.toggle('hidden');
      toggleBtn.textContent = bulkBox.classList.contains('hidden') ? '+ افزودن چند لینک با هم' : '− بستن پنجره چند لینک';
    });

    // رویداد کلیک دکمهٔ تبدیل
    document.getElementById('btn-parse-bulk').addEventListener('click', () => {
      const raw = document.getElementById('in-url-bulk').value.trim();
      if (!raw) { alert('حداقل یه خط وارد کن'); return; }
      const lines = raw.split(/\n/).map(l => l.trim()).filter(Boolean);
      let added = 0;
      lines.forEach((line) => {
        const parts = line.split(/\s+/);
        const url = parts[0];
        let label = parts.slice(1).join(' ') || '';
        if (!url.startsWith('http')) return;
        if (!label) {
          const m = url.match(/[_-](\d{3,4}p)[._-]?/i);
          label = m ? m[1] : ('لینک ' + (added + 1));
        }
        // فراخوانی تابع addLinkRow که توی index.html تعریف شده
        if (typeof addLinkRow === 'function') {
          addLinkRow(label, url);
          added++;
        }
      });
      if (added) {
        alert(added + ' لینک اضافه شد');
        document.getElementById('in-url-bulk').value = '';
        bulkBox.classList.add('hidden');
        toggleBtn.textContent = '+ افزودن چند لینک با هم';
      } else {
        alert('هیچ لینک معتبری پیدا نشد');
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
