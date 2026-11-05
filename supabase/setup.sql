-- Run once in Supabase: Dashboard > SQL Editor > New query > paste > Run.
create table if not exists public.planner (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  data       jsonb not null,
  updated_at timestamptz not null default now()
);

alter table public.planner enable row level security;

-- every person can see and change only their own row
drop policy if exists "planner_select_own" on public.planner;
drop policy if exists "planner_insert_own" on public.planner;
drop policy if exists "planner_update_own" on public.planner;
drop policy if exists "planner_delete_own" on public.planner;
create policy "planner_select_own" on public.planner for select using (auth.uid() = user_id);
create policy "planner_insert_own" on public.planner for insert with check (auth.uid() = user_id);
create policy "planner_update_own" on public.planner for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "planner_delete_own" on public.planner for delete using (auth.uid() = user_id);

-- only signed-in users may touch the table (needed when "Automatically expose new tables" is off; harmless when it is on)
revoke all on public.planner from anon;
grant select, insert, update, delete on public.planner to authenticated;


-- =====================================================================
-- v271: link a counselor to a student with an invite code, and send study plans
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.links (
  id              uuid primary key default gen_random_uuid(),
  code            text not null unique,
  counselor_id    uuid not null references auth.users(id) on delete cascade,
  counselor_name  text not null default '',
  student_id      uuid references auth.users(id) on delete set null,
  student_name    text not null default '',
  created_at      timestamptz not null default now(),
  linked_at       timestamptz,
  expires_at      timestamptz not null default now() + interval '14 days'
);
alter table public.links enable row level security;
drop policy if exists "links_counselor_all"    on public.links;
drop policy if exists "links_counselor_select" on public.links;
drop policy if exists "links_counselor_insert" on public.links;
drop policy if exists "links_counselor_update" on public.links;
drop policy if exists "links_counselor_delete" on public.links;
drop policy if exists "links_student_select"   on public.links;
drop policy if exists "links_student_delete"   on public.links;
create policy "links_counselor_select" on public.links for select using (auth.uid() = counselor_id);
-- a new link always starts without a student; only redeem_link() can set student_id
create policy "links_counselor_insert" on public.links for insert with check (auth.uid() = counselor_id and student_id is null and linked_at is null);
create policy "links_counselor_update" on public.links for update using (auth.uid() = counselor_id) with check (auth.uid() = counselor_id);
create policy "links_counselor_delete" on public.links for delete using (auth.uid() = counselor_id);
create policy "links_student_select"   on public.links for select using (auth.uid() = student_id);
create policy "links_student_delete"   on public.links for delete using (auth.uid() = student_id);
revoke all on public.links from anon;
revoke update on public.links from authenticated;
grant select, insert, delete on public.links to authenticated;
grant update (student_name, counselor_name) on public.links to authenticated;

create table if not exists public.shared_plans (
  id            uuid primary key default gen_random_uuid(),
  link_id       uuid not null references public.links(id) on delete cascade,
  counselor_id  uuid not null references auth.users(id) on delete cascade,
  student_id    uuid not null references auth.users(id) on delete cascade,
  plan_key      text not null default '',
  title         text not null default '',
  data          jsonb not null,
  sent_at       timestamptz not null default now(),
  seen_at       timestamptz
);
create index if not exists shared_plans_student_idx   on public.shared_plans (student_id, seen_at);
create index if not exists shared_plans_counselor_idx on public.shared_plans (counselor_id, sent_at desc);
alter table public.shared_plans enable row level security;
drop policy if exists "sp_counselor_insert" on public.shared_plans;
drop policy if exists "sp_counselor_select" on public.shared_plans;
drop policy if exists "sp_counselor_delete" on public.shared_plans;
drop policy if exists "sp_student_select"   on public.shared_plans;
drop policy if exists "sp_student_update"   on public.shared_plans;
-- the counselor may only send to a student who is really linked to him / her
create policy "sp_counselor_insert" on public.shared_plans for insert with check (
  auth.uid() = counselor_id and exists (
    select 1 from public.links l where l.id = link_id and l.counselor_id = auth.uid() and l.student_id = shared_plans.student_id));
create policy "sp_counselor_select" on public.shared_plans for select using (auth.uid() = counselor_id);
create policy "sp_counselor_delete" on public.shared_plans for delete using (auth.uid() = counselor_id);
create policy "sp_student_select"   on public.shared_plans for select using (auth.uid() = student_id);
create policy "sp_student_update"   on public.shared_plans for update using (auth.uid() = student_id) with check (auth.uid() = student_id);
revoke all on public.shared_plans from anon;
revoke update on public.shared_plans from authenticated;
grant select, insert, delete on public.shared_plans to authenticated;
grant update (seen_at) on public.shared_plans to authenticated;   -- the student can only mark a plan as seen

-- the student types a code: only this function can attach him / her to the link (the table itself stays closed)
create or replace function public.redeem_link(p_code text, p_name text default '')
returns json language plpgsql security definer set search_path = public as $$
declare l public.links;
begin
  if auth.uid() is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  select * into l from public.links where code = upper(trim(p_code)) for update;
  if not found then return json_build_object('ok', false, 'err', 'notfound'); end if;
  if l.counselor_id = auth.uid() then return json_build_object('ok', false, 'err', 'self'); end if;
  if l.student_id is not null and l.student_id <> auth.uid() then return json_build_object('ok', false, 'err', 'used'); end if;
  if l.student_id is null and l.expires_at < now() then return json_build_object('ok', false, 'err', 'expired'); end if;
  update public.links
     set student_id = auth.uid(),
         linked_at = coalesce(linked_at, now()),
         student_name = case when coalesce(student_name, '') = '' and coalesce(trim(p_name), '') <> '' then left(trim(p_name), 80) else student_name end   -- the name the counselor typed wins
   where id = l.id;
  return json_build_object('ok', true, 'link_id', l.id, 'counselor_name', l.counselor_name);
end $$;
revoke all on function public.redeem_link(text, text) from public, anon;
grant execute on function public.redeem_link(text, text) to authenticated;


-- =====================================================================
-- v272: the student sends a daily report to the counselor; the counselor sees a simple dashboard
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.student_reports (
  id            uuid primary key default gen_random_uuid(),
  link_id       uuid not null references public.links(id) on delete cascade,
  student_id    uuid not null references auth.users(id) on delete cascade,
  counselor_id  uuid not null references auth.users(id) on delete cascade,
  day           date not null,
  sp            int  not null default 0,   -- study minutes
  cl            int  not null default 0,   -- class minutes
  te            int  not null default 0,   -- tests
  tc            int  not null default 0,   -- tests that have an answer key
  cr            int  not null default 0,   -- correct answers among tc
  parts         int  not null default 0,
  body          text not null default '',  -- the report form text
  sent_at       timestamptz not null default now(),
  seen_at       timestamptz,
  unique (link_id, day)
);
create index if not exists student_reports_counselor_idx on public.student_reports (counselor_id, day desc);
create index if not exists student_reports_student_idx   on public.student_reports (student_id, day desc);
alter table public.student_reports enable row level security;
drop policy if exists "sr_student_select"   on public.student_reports;
drop policy if exists "sr_counselor_select" on public.student_reports;
drop policy if exists "sr_student_delete"   on public.student_reports;
create policy "sr_student_select"   on public.student_reports for select using (auth.uid() = student_id);
create policy "sr_counselor_select" on public.student_reports for select using (auth.uid() = counselor_id);
create policy "sr_student_delete"   on public.student_reports for delete using (auth.uid() = student_id);
revoke all on public.student_reports from anon;
revoke insert, update on public.student_reports from authenticated;   -- writes go only through the two functions below
grant select, delete on public.student_reports to authenticated;

-- the student sends (or re-sends) the report of one day; only to a counselor he / she is really linked to
create or replace function public.send_report(
  p_link uuid, p_day date, p_sp int, p_cl int, p_te int, p_tc int, p_cr int, p_parts int, p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare l public.links;
begin
  if auth.uid() is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  select * into l from public.links where id = p_link and student_id = auth.uid();
  if not found then return json_build_object('ok', false, 'err', 'nolink'); end if;
  if p_day is null or p_day > current_date + 1 or p_day < current_date - 60 then return json_build_object('ok', false, 'err', 'day'); end if;
  insert into public.student_reports (link_id, student_id, counselor_id, day, sp, cl, te, tc, cr, parts, body)
  values (l.id, auth.uid(), l.counselor_id, p_day,
          least(greatest(coalesce(p_sp, 0), 0), 1440), least(greatest(coalesce(p_cl, 0), 0), 1440),
          least(greatest(coalesce(p_te, 0), 0), 5000), least(greatest(coalesce(p_tc, 0), 0), 5000),
          least(greatest(coalesce(p_cr, 0), 0), 5000), least(greatest(coalesce(p_parts, 0), 0), 6),
          left(coalesce(p_body, ''), 9000))
  on conflict (link_id, day) do update
     set sp = excluded.sp, cl = excluded.cl, te = excluded.te, tc = excluded.tc, cr = excluded.cr,
         parts = excluded.parts, body = excluded.body, sent_at = now(), seen_at = null;
  return json_build_object('ok', true);
end $$;
revoke all on function public.send_report(uuid, date, int, int, int, int, int, int, text) from public, anon;
grant execute on function public.send_report(uuid, date, int, int, int, int, int, int, text) to authenticated;

-- the counselor marks one report (or, with a null day, all of a student's reports) as seen
create or replace function public.mark_reports_seen(p_link uuid, p_day date default null)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.student_reports set seen_at = now()
   where counselor_id = auth.uid() and link_id = p_link and seen_at is null and (p_day is null or day = p_day);
  get diagnostics n = row_count;
  return n;
end $$;
revoke all on function public.mark_reports_seen(uuid, date) from public, anon;
grant execute on function public.mark_reports_seen(uuid, date) to authenticated;


-- =====================================================================
-- v273: ending a link (by the counselor or by the student) also deletes that link's reports and sent plans for the counselor.
-- The foreign keys already cascade; this trigger makes it explicit and keeps it true even if a table was created without the cascade.
-- Run this block once (it is safe to run again).
-- =====================================================================
create or replace function public.links_cleanup()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  delete from public.student_reports where link_id = old.id;
  delete from public.shared_plans    where link_id = old.id;
  return old;
end $$;
revoke all on function public.links_cleanup() from public, anon, authenticated;
drop trigger if exists links_cleanup_trg on public.links;
create trigger links_cleanup_trg before delete on public.links
  for each row execute function public.links_cleanup();
