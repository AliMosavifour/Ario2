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


-- =====================================================================
-- v277: «من و مشاور» - a private chat between a counselor and a linked student
-- (text, photos, videos, voice / round-video messages, files and daily reports).
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.link_messages (
  id          uuid primary key default gen_random_uuid(),
  link_id     uuid not null references public.links(id) on delete cascade,
  sender_id   uuid not null references auth.users(id) on delete cascade,
  kind        text not null check (kind in ('t','p','v','a','r','f','g')),   -- t text · p photo · v video · a voice · r round video · f file · g daily report
  body        text not null default '' check (char_length(body) <= 9000),
  media       text,                                                          -- path in the private "chat" bucket:  <link_id>/<sender_id>/<message id>.<ext>
  thumb       text,                                                          -- small picture for photos / videos
  mname       text,
  msize       bigint,
  meta        jsonb not null default '{}'::jsonb,                            -- duration, waveform, report numbers...
  created_at  timestamptz not null default now(),
  seen_at     timestamptz
);
create index if not exists link_messages_link_idx   on public.link_messages (link_id, created_at desc);
create index if not exists link_messages_unseen_idx on public.link_messages (link_id) where seen_at is null;
alter table public.link_messages enable row level security;
drop policy if exists "lm_select" on public.link_messages;
drop policy if exists "lm_insert" on public.link_messages;
drop policy if exists "lm_delete" on public.link_messages;
-- only the two people of a link that really has a student can read / write it
create policy "lm_select" on public.link_messages for select using (
  exists (select 1 from public.links l where l.id = link_id and l.student_id is not null and auth.uid() in (l.counselor_id, l.student_id)));
create policy "lm_insert" on public.link_messages for insert with check (
  sender_id = auth.uid() and exists (select 1 from public.links l where l.id = link_id and l.student_id is not null and auth.uid() in (l.counselor_id, l.student_id)));
create policy "lm_delete" on public.link_messages for delete using (sender_id = auth.uid());
revoke all on public.link_messages from anon;
revoke insert, update on public.link_messages from authenticated;
grant select, delete on public.link_messages to authenticated;
grant insert (id, link_id, sender_id, kind, body, media, thumb, mname, msize, meta) on public.link_messages to authenticated;   -- created_at / seen_at can not be forged

-- the receiver marks everything the other person sent as seen
create or replace function public.mark_chat_seen(p_link uuid)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.link_messages m set seen_at = now()
   where m.link_id = p_link and m.sender_id <> auth.uid() and m.seen_at is null
     and exists (select 1 from public.links l where l.id = p_link and auth.uid() in (l.counselor_id, l.student_id));
  get diagnostics n = row_count;
  return n;
end $$;
revoke all on function public.mark_chat_seen(uuid) from public, anon;
grant execute on function public.mark_chat_seen(uuid) to authenticated;

-- private bucket for the files (50 MB each); path = <link_id>/<sender_id>/<file>
insert into storage.buckets (id, name, public, file_size_limit)
values ('chat', 'chat', false, 52428800)
on conflict (id) do update set public = false, file_size_limit = 52428800;
drop policy if exists "chat_obj_select" on storage.objects;
drop policy if exists "chat_obj_insert" on storage.objects;
drop policy if exists "chat_obj_delete" on storage.objects;
create policy "chat_obj_select" on storage.objects for select to authenticated using (
  bucket_id = 'chat' and exists (select 1 from public.links l
    where l.id::text = (storage.foldername(name))[1] and l.student_id is not null and auth.uid() in (l.counselor_id, l.student_id)));
create policy "chat_obj_insert" on storage.objects for insert to authenticated with check (
  bucket_id = 'chat' and (storage.foldername(name))[2] = auth.uid()::text and exists (select 1 from public.links l
    where l.id::text = (storage.foldername(name))[1] and l.student_id is not null and auth.uid() in (l.counselor_id, l.student_id)));
create policy "chat_obj_delete" on storage.objects for delete to authenticated using (
  bucket_id = 'chat' and (storage.foldername(name))[2] = auth.uid()::text);


-- =====================================================================
-- v279: friend chat - two ordinary users connect with a code and chat / share everything
-- (uses the same message table and "chat" bucket as the counselor chat). Run this block once (safe to run again).
-- =====================================================================
create table if not exists public.friend_links (
  id          uuid primary key default gen_random_uuid(),
  code        text not null unique,
  owner_id    uuid not null references auth.users(id) on delete cascade,
  owner_name  text not null default '',
  friend_id   uuid references auth.users(id) on delete cascade,
  friend_name text not null default '',
  created_at  timestamptz not null default now(),
  linked_at   timestamptz,
  expires_at  timestamptz not null default (now() + interval '7 days')
);
create index if not exists friend_links_owner_idx  on public.friend_links (owner_id);
create index if not exists friend_links_friend_idx on public.friend_links (friend_id);
alter table public.friend_links enable row level security;
drop policy if exists "fl_select" on public.friend_links;
drop policy if exists "fl_insert" on public.friend_links;
drop policy if exists "fl_update" on public.friend_links;
drop policy if exists "fl_delete" on public.friend_links;
create policy "fl_select" on public.friend_links for select using (auth.uid() in (owner_id, friend_id));
-- a new code always starts without a friend; only redeem_friend() can attach one
create policy "fl_insert" on public.friend_links for insert with check (auth.uid() = owner_id and friend_id is null and linked_at is null);
create policy "fl_update" on public.friend_links for update using (auth.uid() in (owner_id, friend_id)) with check (auth.uid() in (owner_id, friend_id));
create policy "fl_delete" on public.friend_links for delete using (auth.uid() in (owner_id, friend_id));
revoke all on public.friend_links from anon;
revoke update on public.friend_links from authenticated;
grant select, insert, delete on public.friend_links to authenticated;
grant update (owner_name, friend_name) on public.friend_links to authenticated;

create or replace function public.redeem_friend(p_code text, p_name text default '')
returns json language plpgsql security definer set search_path = public as $$
declare f public.friend_links;
begin
  if auth.uid() is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  select * into f from public.friend_links where code = upper(trim(p_code)) for update;
  if not found then return json_build_object('ok', false, 'err', 'notfound'); end if;
  if f.owner_id = auth.uid() then return json_build_object('ok', false, 'err', 'self'); end if;
  if f.friend_id is not null and f.friend_id <> auth.uid() then return json_build_object('ok', false, 'err', 'used'); end if;
  if f.friend_id is null and f.expires_at < now() then return json_build_object('ok', false, 'err', 'expired'); end if;
  update public.friend_links
     set friend_id = auth.uid(), linked_at = coalesce(linked_at, now()),
         friend_name = case when coalesce(trim(p_name), '') <> '' then left(trim(p_name), 80) else friend_name end
   where id = f.id;
  return json_build_object('ok', true, 'link_id', f.id, 'owner_name', f.owner_name);
end $$;
revoke all on function public.redeem_friend(text, text) from public, anon;
grant execute on function public.redeem_friend(text, text) to authenticated;

-- one place that says "this person belongs to this chat" (counselor link OR friend link)
create or replace function public.chat_member(p_id text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare u uuid;
begin
  begin u := p_id::uuid; exception when others then return false; end;
  return exists (select 1 from public.links l where l.id = u and l.student_id is not null and auth.uid() in (l.counselor_id, l.student_id))
      or exists (select 1 from public.friend_links f where f.id = u and f.friend_id is not null and auth.uid() in (f.owner_id, f.friend_id));
end $$;
revoke all on function public.chat_member(text) from public, anon;
grant execute on function public.chat_member(text) to authenticated;

-- messages may now belong to either kind of link, so the foreign key goes; the delete triggers below clean up instead
alter table public.link_messages drop constraint if exists link_messages_link_id_fkey;
drop policy if exists "lm_select" on public.link_messages;
drop policy if exists "lm_insert" on public.link_messages;
create policy "lm_select" on public.link_messages for select using (public.chat_member(link_id::text));
create policy "lm_insert" on public.link_messages for insert with check (sender_id = auth.uid() and public.chat_member(link_id::text));

create or replace function public.mark_chat_seen(p_link uuid)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if not public.chat_member(p_link::text) then return 0; end if;
  update public.link_messages m set seen_at = now()
   where m.link_id = p_link and m.sender_id <> auth.uid() and m.seen_at is null;
  get diagnostics n = row_count;
  return n;
end $$;

drop policy if exists "chat_obj_select" on storage.objects;
drop policy if exists "chat_obj_insert" on storage.objects;
create policy "chat_obj_select" on storage.objects for select to authenticated using (
  bucket_id = 'chat' and public.chat_member((storage.foldername(name))[1]));
create policy "chat_obj_insert" on storage.objects for insert to authenticated with check (
  bucket_id = 'chat' and (storage.foldername(name))[2] = auth.uid()::text and public.chat_member((storage.foldername(name))[1]));

create or replace function public.links_cleanup()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  delete from public.student_reports where link_id = old.id;
  delete from public.shared_plans    where link_id = old.id;
  delete from public.link_messages   where link_id = old.id;
  return old;
end $$;
create or replace function public.friend_links_cleanup()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  delete from public.link_messages where link_id = old.id;
  return old;
end $$;
revoke all on function public.friend_links_cleanup() from public, anon, authenticated;
drop trigger if exists friend_links_cleanup_trg on public.friend_links;
create trigger friend_links_cleanup_trg before delete on public.friend_links
  for each row execute function public.friend_links_cleanup();


-- =====================================================================
-- v295: safer multi-device sync. Every task / setting is its own row; the newest change of the SAME item wins.
-- (The old one-row-per-person table "planner" is kept untouched; planner_migrate() copies it once into the new table.)
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.planner_items (
  user_id uuid   not null references auth.users(id) on delete cascade,
  k       text   not null check (char_length(k) between 3 and 200),   -- 't:<task id>' · 'p:<profile key>' · 'h:<history key>' · 'c:cats'
  data    jsonb,                                                       -- null only for a deleted item
  mt      bigint not null,                                             -- time of the change (ms, from the phone, corrected to server time); the larger mt wins
  del     boolean not null default false,                              -- a deleted item stays as a "tombstone" so the delete reaches the other phones
  srv     timestamptz not null default clock_timestamp(),              -- when the server stored it: phones ask "what is newer than my last look?"
  primary key (user_id, k)
);
create index if not exists planner_items_srv_idx on public.planner_items (user_id, srv, k);
alter table public.planner_items enable row level security;
drop policy if exists "pi_select_own" on public.planner_items;
create policy "pi_select_own" on public.planner_items for select using (auth.uid() = user_id);
revoke all on public.planner_items from anon, authenticated;
grant select on public.planner_items to authenticated;   -- writes go only through planner_push()

-- fixed-width UTC text (so two cursors can be compared as plain strings on the phone)
create or replace function public.planner_ts(t timestamptz)
returns text language sql immutable set search_path = public as $$
  select to_char(t at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;
revoke all on function public.planner_ts(timestamptz) from public, anon, authenticated;

-- save a batch of items; an item is stored only when its mt is newer than the stored one. Answers with the items that lost, so the phone can adopt them.
create or replace function public.planner_push(p_items jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  it jsonb; v_k text; v_mt bigint; v_del boolean; v_data jsonb;
  nowms bigint; rej jsonb := '[]'::jsonb; cur public.planner_items; n int;
begin
  if uid is null then raise exception 'auth'; end if;
  if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) > 500 then raise exception 'bad batch'; end if;
  nowms := (extract(epoch from clock_timestamp()) * 1000)::bigint;
  for it in select * from jsonb_array_elements(p_items) loop
    v_k := it->>'k';
    if v_k is null or char_length(v_k) not between 3 and 200 or v_k !~ '^[tphc]:' then continue; end if;
    v_del := coalesce((it->>'del')::boolean, false);
    v_data := case when v_del then null else it->'data' end;
    if not v_del and v_data is null then continue; end if;
    if v_data is not null and pg_column_size(v_data) > 4000000 then raise exception 'item too large'; end if;
    -- a phone with a wrong clock cannot win forever: a change cannot be dated more than 2 minutes ahead of the server
    v_mt := least(greatest(coalesce((it->>'mt')::bigint, 1), 1), nowms + 120000);
    insert into public.planner_items as t (user_id, k, data, mt, del, srv)
    values (uid, v_k, v_data, v_mt, v_del, clock_timestamp())
    on conflict (user_id, k) do update
      set data = excluded.data, mt = excluded.mt, del = excluded.del, srv = excluded.srv
      where t.mt < excluded.mt;
    get diagnostics n = row_count;
    if n = 0 then
      select * into cur from public.planner_items where user_id = uid and k = v_k;
      rej := rej || jsonb_build_array(jsonb_build_object('k', cur.k, 'data', cur.data, 'mt', cur.mt, 'del', cur.del, 'srv', public.planner_ts(cur.srv)));
    end if;
  end loop;
  return json_build_object('now', nowms, 'rejected', rej);
end $$;
revoke all on function public.planner_push(jsonb) from public, anon;
grant execute on function public.planner_push(jsonb) to authenticated;

-- what changed since the phone's last look (keyset pages of p_limit; p_back re-reads the last few seconds so a slow commit is never missed)
create or replace function public.planner_pull(p_since text default '1970-01-01T00:00:00Z', p_k text default '', p_limit int default 1000, p_back int default 0)
returns json language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  lim int := least(greatest(coalesce(p_limit, 1000), 1), 2000);
  since timestamptz; arr json; cnt int;
begin
  if uid is null then raise exception 'auth'; end if;
  since := p_since::timestamptz - make_interval(secs => least(greatest(coalesce(p_back, 0), 0), 120));
  select coalesce(json_agg(json_build_object('k', k, 'data', data, 'mt', mt, 'del', del, 'srv', public.planner_ts(srv)) order by srv, k), '[]'::json), count(*)
    into arr, cnt
    from (select * from public.planner_items
           where user_id = uid and (srv, k) > (since, coalesce(p_k, ''))
           order by srv, k limit lim + 1) q;
  -- we asked for one extra row only to know whether there is another page
  if cnt > lim then
    select json_agg(e) into arr from (select e from json_array_elements(arr) with ordinality as x(e, i) where i <= lim order by i) z;
  end if;
  return json_build_object('now', (extract(epoch from clock_timestamp()) * 1000)::bigint, 'items', arr, 'more', cnt > lim);
end $$;
revoke all on function public.planner_pull(text, text, int, int) from public, anon;
grant execute on function public.planner_pull(text, text, int, int) to authenticated;

-- one-time copy of the old whole-planner row into items (does nothing when this person already has items or has no old row)
create or replace function public.planner_migrate()
returns int language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid(); d jsonb; ts bigint; n int := 0; c int;
begin
  if uid is null then raise exception 'auth'; end if;
  perform pg_advisory_xact_lock(hashtext('planner_migrate:' || uid::text));
  if exists (select 1 from public.planner_items where user_id = uid) then return 0; end if;
  select data, (extract(epoch from updated_at) * 1000)::bigint into d, ts from public.planner where user_id = uid;
  if d is null or jsonb_typeof(d) <> 'object' then return 0; end if;
  if jsonb_typeof(d->'tasks') = 'array' then
    insert into public.planner_items (user_id, k, data, mt, del)
    select uid, 't:' || (t->>'id'), t, ts, false
      from jsonb_array_elements(d->'tasks') t
     where jsonb_typeof(t) = 'object' and coalesce(t->>'id', '') <> '' and char_length('t:' || (t->>'id')) <= 200
       and coalesce(t->>'s', '') not in ('1', 'true')     -- the sample tasks of a fresh install are not real data
    on conflict do nothing;
    get diagnostics c = row_count; n := n + c;
  end if;
  if jsonb_typeof(d->'profile') = 'object' then
    insert into public.planner_items (user_id, k, data, mt, del)
    select uid, 'p:' || key, value, ts, false from jsonb_each(d->'profile') where char_length('p:' || key) <= 200
    on conflict do nothing;
    get diagnostics c = row_count; n := n + c;
  end if;
  if jsonb_typeof(d->'cats') = 'array' then
    insert into public.planner_items (user_id, k, data, mt, del) values (uid, 'c:cats', d->'cats', ts, false) on conflict do nothing;
    get diagnostics c = row_count; n := n + c;
  end if;
  if jsonb_typeof(d->'hist') = 'object' then
    insert into public.planner_items (user_id, k, data, mt, del)
    select uid, 'h:' || key, value, ts, false from jsonb_each(d->'hist') where jsonb_typeof(value) = 'object' and char_length('h:' || key) <= 200
    on conflict do nothing;
    get diagnostics c = row_count; n := n + c;
  end if;
  return n;
end $$;
revoke all on function public.planner_migrate() from public, anon;
grant execute on function public.planner_migrate() to authenticated;


-- =====================================================================
-- v296: friend groups. A group is one more kind of chat: its id is used as link_id in public.link_messages,
-- so text, photos, videos, voice, files and the "chat" bucket work the same way as in a one-to-one chat.
-- Only a person's own linked friends can be put in a group (create_group / group_add check friend_links).
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.chat_groups (
  id         uuid primary key default gen_random_uuid(),
  name       text not null check (char_length(name) between 1 and 60),
  owner_id   uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table if not exists public.chat_group_members (
  group_id   uuid not null references public.chat_groups(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null default '',
  joined_at  timestamptz not null default now(),
  last_read  timestamptz not null default now(),
  primary key (group_id, user_id)
);
create index if not exists chat_group_members_user_idx on public.chat_group_members (user_id);
alter table public.chat_groups enable row level security;
alter table public.chat_group_members enable row level security;

create or replace function public.group_member(p_id uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.chat_group_members g where g.group_id = p_id and g.user_id = auth.uid());
$$;
revoke all on function public.group_member(uuid) from public, anon;
grant execute on function public.group_member(uuid) to authenticated;

drop policy if exists "cg_select"  on public.chat_groups;
drop policy if exists "cgm_select" on public.chat_group_members;
create policy "cg_select"  on public.chat_groups        for select using (public.group_member(id));
create policy "cgm_select" on public.chat_group_members for select using (public.group_member(group_id));
revoke all on public.chat_groups, public.chat_group_members from anon;
revoke insert, update, delete on public.chat_groups, public.chat_group_members from authenticated;
grant select on public.chat_groups, public.chat_group_members to authenticated;   -- every change goes through the functions below

-- are these two people linked as friends? and what is the friend called (as written on their friend link)
create or replace function public.friend_of(p_user uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.friend_links f where f.friend_id is not null
    and ((f.owner_id = auth.uid() and f.friend_id = p_user) or (f.friend_id = auth.uid() and f.owner_id = p_user)));
$$;
create or replace function public.friend_name_of(p_user uuid)
returns text language sql stable security definer set search_path = public as $$
  select left(case when f.owner_id = auth.uid() then f.friend_name else f.owner_name end, 80)
    from public.friend_links f where f.friend_id is not null
     and ((f.owner_id = auth.uid() and f.friend_id = p_user) or (f.friend_id = auth.uid() and f.owner_id = p_user))
   limit 1;
$$;
revoke all on function public.friend_of(uuid), public.friend_name_of(uuid) from public, anon;
grant execute on function public.friend_of(uuid), public.friend_name_of(uuid) to authenticated;

create or replace function public.create_group(p_name text, p_members uuid[], p_my_name text default '')
returns json language plpgsql security definer set search_path = public as $$
declare gid uuid; u uuid; ms uuid[]; nm text := left(trim(coalesce(p_name, '')), 60);
begin
  if auth.uid() is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if nm = '' then return json_build_object('ok', false, 'err', 'name'); end if;
  select coalesce(array_agg(distinct x), '{}') into ms from unnest(coalesce(p_members, '{}')) x where x is not null and x <> auth.uid();
  if coalesce(array_length(ms, 1), 0) < 1 then return json_build_object('ok', false, 'err', 'members'); end if;
  if array_length(ms, 1) > 49 then return json_build_object('ok', false, 'err', 'full'); end if;
  foreach u in array ms loop
    if not public.friend_of(u) then return json_build_object('ok', false, 'err', 'notfriend'); end if;
  end loop;
  insert into public.chat_groups (name, owner_id) values (nm, auth.uid()) returning id into gid;
  insert into public.chat_group_members (group_id, user_id, name) values (gid, auth.uid(), left(coalesce(trim(p_my_name), ''), 80));
  foreach u in array ms loop
    insert into public.chat_group_members (group_id, user_id, name) values (gid, u, coalesce(public.friend_name_of(u), ''));
  end loop;
  return json_build_object('ok', true, 'id', gid);
end $$;

create or replace function public.group_add(p_group uuid, p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.chat_groups where id = p_group and owner_id = auth.uid()) then return json_build_object('ok', false, 'err', 'owner'); end if;
  if not public.friend_of(p_user) then return json_build_object('ok', false, 'err', 'notfriend'); end if;
  if exists (select 1 from public.chat_group_members where group_id = p_group and user_id = p_user) then return json_build_object('ok', true); end if;
  if (select count(*) from public.chat_group_members where group_id = p_group) >= 50 then return json_build_object('ok', false, 'err', 'full'); end if;
  insert into public.chat_group_members (group_id, user_id, name) values (p_group, p_user, coalesce(public.friend_name_of(p_user), ''));
  return json_build_object('ok', true);
end $$;

create or replace function public.group_remove(p_group uuid, p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.chat_groups where id = p_group and owner_id = auth.uid()) then return json_build_object('ok', false, 'err', 'owner'); end if;
  if p_user = auth.uid() then return json_build_object('ok', false, 'err', 'self'); end if;
  delete from public.chat_group_members where group_id = p_group and user_id = p_user;
  return json_build_object('ok', true);
end $$;

create or replace function public.group_rename(p_group uuid, p_name text)
returns json language plpgsql security definer set search_path = public as $$
declare nm text := left(trim(coalesce(p_name, '')), 60);
begin
  if nm = '' then return json_build_object('ok', false, 'err', 'name'); end if;
  update public.chat_groups set name = nm where id = p_group and owner_id = auth.uid();
  if not found then return json_build_object('ok', false, 'err', 'owner'); end if;
  return json_build_object('ok', true);
end $$;

-- leaving: the oldest remaining member becomes the owner; the last one out deletes the group and its messages
create or replace function public.group_leave(p_group uuid)
returns json language plpgsql security definer set search_path = public as $$
declare was_owner boolean; nxt uuid;
begin
  if not public.group_member(p_group) then return json_build_object('ok', false, 'err', 'member'); end if;
  select owner_id = auth.uid() into was_owner from public.chat_groups where id = p_group;
  delete from public.chat_group_members where group_id = p_group and user_id = auth.uid();
  select user_id into nxt from public.chat_group_members where group_id = p_group order by joined_at asc limit 1;
  if nxt is null then
    delete from public.chat_groups where id = p_group;
  elsif was_owner then
    update public.chat_groups set owner_id = nxt where id = p_group;
  end if;
  return json_build_object('ok', true);
end $$;
revoke all on function public.create_group(text, uuid[], text), public.group_add(uuid, uuid), public.group_remove(uuid, uuid),
  public.group_rename(uuid, text), public.group_leave(uuid) from public, anon;
grant execute on function public.create_group(text, uuid[], text), public.group_add(uuid, uuid), public.group_remove(uuid, uuid),
  public.group_rename(uuid, text), public.group_leave(uuid) to authenticated;

-- a group chat belongs to its members (plus the two older kinds of chat)
create or replace function public.chat_member(p_id text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare u uuid;
begin
  begin u := p_id::uuid; exception when others then return false; end;
  return exists (select 1 from public.links l where l.id = u and l.student_id is not null and auth.uid() in (l.counselor_id, l.student_id))
      or exists (select 1 from public.friend_links f where f.id = u and f.friend_id is not null and auth.uid() in (f.owner_id, f.friend_id))
      or exists (select 1 from public.chat_group_members g where g.group_id = u and g.user_id = auth.uid());
end $$;

-- in a group "read" is kept per member (last_read), not per message
create or replace function public.mark_chat_seen(p_link uuid)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if not public.chat_member(p_link::text) then return 0; end if;
  if exists (select 1 from public.chat_groups where id = p_link) then
    update public.chat_group_members set last_read = now() where group_id = p_link and user_id = auth.uid();
    return 0;
  end if;
  update public.link_messages m set seen_at = now()
   where m.link_id = p_link and m.sender_id <> auth.uid() and m.seen_at is null;
  get diagnostics n = row_count;
  return n;
end $$;

create or replace function public.chat_groups_cleanup()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  delete from public.link_messages where link_id = old.id;
  return old;
end $$;
revoke all on function public.chat_groups_cleanup() from public, anon, authenticated;
drop trigger if exists chat_groups_cleanup_trg on public.chat_groups;
create trigger chat_groups_cleanup_trg before delete on public.chat_groups
  for each row execute function public.chat_groups_cleanup();


-- =====================================================================
-- v298: polls in chats (the paper-clip menu → «نظرسنجی»). A poll is a link_messages row with kind 'q':
--   body = the question, meta = {o:[options], mu:multiple answers, rv:revoting, sh:shuffle, ok:index of the right answer (quiz) or -1}
-- Votes live in link_poll_votes (one row per voter). They are written only through poll_vote(), which checks the poll's rules.
-- Works in every chat kind (advisor, friend, group). Run this block once (it is safe to run again).
-- =====================================================================
alter table public.link_messages drop constraint if exists link_messages_kind_check;
alter table public.link_messages add constraint link_messages_kind_check check (kind in ('t','p','v','a','r','f','g','q'));

create table if not exists public.link_poll_votes (
  msg_id   uuid not null references public.link_messages(id) on delete cascade,
  user_id  uuid not null references auth.users(id) on delete cascade,
  opts     int[] not null default '{}',
  voted_at timestamptz not null default now(),
  primary key (msg_id, user_id)
);
alter table public.link_poll_votes enable row level security;
drop policy if exists "lpv_select" on public.link_poll_votes;
create policy "lpv_select" on public.link_poll_votes for select using (
  exists (select 1 from public.link_messages m where m.id = msg_id and public.chat_member(m.link_id::text)));
revoke all on public.link_poll_votes from anon, authenticated;
grant select on public.link_poll_votes to authenticated;

-- vote (p_opts = chosen option numbers, 0-based) or take the vote back (empty array, only when revoting is allowed)
create or replace function public.poll_vote(p_msg uuid, p_opts int[])
returns jsonb language plpgsql security definer set search_path = public as $$
declare m public.link_messages; n int; quiz boolean; had boolean;
begin
  select * into m from public.link_messages where id = p_msg and kind = 'q';
  if not found or not public.chat_member(m.link_id::text) then return jsonb_build_object('ok', false, 'error', 'nopoll'); end if;
  n := jsonb_array_length(coalesce(m.meta->'o', '[]'::jsonb));
  quiz := coalesce((m.meta->>'ok')::int, -1) >= 0;
  select array(select distinct x from unnest(coalesce(p_opts, '{}'::int[])) x order by x) into p_opts;
  if exists (select 1 from unnest(p_opts) x where x < 0 or x >= n) then return jsonb_build_object('ok', false, 'error', 'range'); end if;
  if coalesce(array_length(p_opts, 1), 0) > 1 and (quiz or not coalesce((m.meta->>'mu')::boolean, false)) then
    return jsonb_build_object('ok', false, 'error', 'single');
  end if;
  had := exists (select 1 from public.link_poll_votes v where v.msg_id = p_msg and v.user_id = auth.uid());
  if had and (quiz or not coalesce((m.meta->>'rv')::boolean, false)) then return jsonb_build_object('ok', false, 'error', 'locked'); end if;
  if coalesce(array_length(p_opts, 1), 0) = 0 then
    delete from public.link_poll_votes where msg_id = p_msg and user_id = auth.uid();
  else
    insert into public.link_poll_votes (msg_id, user_id, opts) values (p_msg, auth.uid(), p_opts)
    on conflict (msg_id, user_id) do update set opts = excluded.opts, voted_at = now();
  end if;
  return jsonb_build_object('ok', true);
end $$;
revoke all on function public.poll_vote(uuid, int[]) from public, anon;
grant execute on function public.poll_vote(uuid, int[]) to authenticated;
