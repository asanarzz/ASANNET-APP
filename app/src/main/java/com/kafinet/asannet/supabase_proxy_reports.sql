-- گزارش نتیجه‌ی تست پروکسی‌ها (کار کرد / کار نکرد) از گوشی کاربران، برای پاک‌سازی خودکارِ خراب‌ها از پنل
-- این فایل را یک بار در Supabase → SQL Editor اجرا کن (Run). فایل supabase_proxies.sql باید قبلاً اجرا شده باشد.

create table if not exists public.proxy_reports (
  id          bigint generated always as identity primary key,
  proxy_key   text not null check (char_length(proxy_key) < 200),   -- مثل socks5://1.2.3.4:1080
  ok          boolean not null,
  ms          integer,
  created_at  timestamptz not null default now()
);

create index if not exists proxy_reports_key_idx on public.proxy_reports (proxy_key);

alter table public.proxy_reports enable row level security;

-- هر کاربر اپ می‌تواند فقط «گزارش اضافه کند» (نه بخواند، نه پاک کند)
drop policy if exists "proxy_reports_insert" on public.proxy_reports;
create policy "proxy_reports_insert" on public.proxy_reports
  for insert to anon, authenticated
  with check (true);

-- خواندن و پاک کردن فقط برای ایمیل مدیر
drop policy if exists "proxy_reports_admin_select" on public.proxy_reports;
create policy "proxy_reports_admin_select" on public.proxy_reports
  for select to authenticated
  using (lower(auth.jwt() ->> 'email') = 'asannettt@gmail.com');

drop policy if exists "proxy_reports_admin_delete" on public.proxy_reports;
create policy "proxy_reports_admin_delete" on public.proxy_reports
  for delete to authenticated
  using (lower(auth.jwt() ->> 'email') = 'asannettt@gmail.com');

-- خلاصه: برای هر پروکسی چند بار کار کرده و چند بار نه
create or replace function public.proxy_report_summary()
returns table (proxy_key text, ok_count bigint, fail_count bigint)
language sql
security invoker
as $$
  select proxy_key,
         count(*) filter (where ok)     as ok_count,
         count(*) filter (where not ok) as fail_count
  from public.proxy_reports
  group by proxy_key;
$$;
