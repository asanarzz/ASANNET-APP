-- جدول پروکسی‌ها برای دکمه‌ی «اتصال به پروکسی» داخل اپ
-- این فایل را یک بار در Supabase → SQL Editor اجرا کن (Run).

create table if not exists public.proxies (
  id          uuid primary key default gen_random_uuid(),
  name        text not null default '',
  type        text not null default 'socks5' check (type in ('socks5', 'http')),
  host        text not null,
  port        integer not null check (port between 1 and 65535),
  username    text,
  password    text,
  enabled     boolean not null default true,
  sort_order  integer not null default 0,
  created_at  timestamptz not null default now(),
  unique (type, host, port)
);

alter table public.proxies enable row level security;

-- همه‌ی کاربران اپ فقط پروکسی‌های «فعال» را می‌بینند؛ مدیر همه را می‌بیند
drop policy if exists "proxies_select" on public.proxies;
create policy "proxies_select" on public.proxies
  for select to anon, authenticated
  using (enabled = true or lower(auth.jwt() ->> 'email') = 'asannettt@gmail.com');

-- افزودن / ویرایش / حذف فقط برای ایمیل مدیر
drop policy if exists "proxies_admin_write" on public.proxies;
create policy "proxies_admin_write" on public.proxies
  for all to authenticated
  using (lower(auth.jwt() ->> 'email') = 'asannettt@gmail.com')
  with check (lower(auth.jwt() ->> 'email') = 'asannettt@gmail.com');
