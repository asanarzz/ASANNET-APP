-- این رو هم تو SQL Editor پنل Supabase اجرا کن تا ویرایش/حذف پیام‌ها کار کنه.

create policy "anon can update support messages"
  on support_messages for update
  to anon
  using (true)
  with check (true);

create policy "anon can delete support messages"
  on support_messages for delete
  to anon
  using (true);
