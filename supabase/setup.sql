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


-- =====================================================================
-- v301: reactions on chat messages (advisor, friend and group chats). One row per person per message;
-- written only through react_set(), read by every member of the chat. Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.link_reactions (
  msg_id     uuid not null references public.link_messages(id) on delete cascade,
  link_id    uuid not null,
  user_id    uuid not null references auth.users(id) on delete cascade,
  emoji      text not null check (char_length(emoji) between 1 and 16),
  created_at timestamptz not null default now(),
  primary key (msg_id, user_id)
);
create index if not exists link_reactions_link_idx on public.link_reactions (link_id);
alter table public.link_reactions enable row level security;
drop policy if exists "lr_select" on public.link_reactions;
create policy "lr_select" on public.link_reactions for select using (public.chat_member(link_id::text));
revoke all on public.link_reactions from anon, authenticated;
grant select on public.link_reactions to authenticated;

-- set my reaction on a message (an empty p_emoji, or the same one again from the phone, takes it back)
create or replace function public.react_set(p_msg uuid, p_emoji text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare m public.link_messages; e text := left(coalesce(trim(p_emoji), ''), 16);
begin
  if auth.uid() is null then return jsonb_build_object('ok', false, 'error', 'auth'); end if;
  select * into m from public.link_messages where id = p_msg;
  if not found or not public.chat_member(m.link_id::text) then return jsonb_build_object('ok', false, 'error', 'nochat'); end if;
  if e = '' then
    delete from public.link_reactions where msg_id = p_msg and user_id = auth.uid();
  else
    insert into public.link_reactions (msg_id, link_id, user_id, emoji) values (p_msg, m.link_id, auth.uid(), e)
    on conflict (msg_id, user_id) do update set emoji = excluded.emoji, created_at = now();
  end if;
  return jsonb_build_object('ok', true);
end $$;
revoke all on function public.react_set(uuid, text) from public, anon;
grant execute on function public.react_set(uuid, text) to authenticated;

notify pgrst, 'reload schema';


-- =====================================================================
-- v304: group photo. The owner of a group picks one of the built-in (non-VIP) avatars (av1..av12) as the group's picture.
-- Run this block once (it is safe to run again).
-- =====================================================================
alter table public.chat_groups add column if not exists photo text not null default '';

create or replace function public.group_set_photo(p_group uuid, p_photo text)
returns json language plpgsql security definer set search_path = public as $$
declare ph text := coalesce(trim(p_photo), '');
begin
  if ph <> '' and ph !~ '^av([1-9]|1[0-2])$' then return json_build_object('ok', false, 'err', 'photo'); end if;
  update public.chat_groups set photo = ph where id = p_group and owner_id = auth.uid();
  if not found then return json_build_object('ok', false, 'err', 'owner'); end if;
  return json_build_object('ok', true);
end $$;
revoke all on function public.group_set_photo(uuid, text) from public, anon;
grant execute on function public.group_set_photo(uuid, text) to authenticated;


-- =====================================================================
-- v305: group photo from the gallery. The photo column may now also hold a small square JPEG as a data URL
-- (made on the phone, 144x144, about 6-10 KB) besides the built-in avatar names. Run this block once (it is safe to run again).
-- =====================================================================
create or replace function public.group_set_photo(p_group uuid, p_photo text)
returns json language plpgsql security definer set search_path = public as $$
declare ph text := coalesce(trim(p_photo), '');
begin
  if ph <> '' and ph !~ '^av([1-9]|1[0-2])$'
     and not (ph ~ '^data:image/jpeg;base64,[A-Za-z0-9+/=]+$' and length(ph) <= 24000) then
    return json_build_object('ok', false, 'err', 'photo');
  end if;
  update public.chat_groups set photo = ph where id = p_group and owner_id = auth.uid();
  if not found then return json_build_object('ok', false, 'err', 'owner'); end if;
  return json_build_object('ok', true);
end $$;
revoke all on function public.group_set_photo(uuid, text) from public, anon;
grant execute on function public.group_set_photo(uuid, text) to authenticated;
notify pgrst, 'reload schema';


-- =====================================================================
-- v311: "online" / "last seen" and "typing…" in chats (advisor, friend and group chats).
-- user_presence = when each person was last active in the app; chat_typing = who is typing in which chat (kept only a few seconds).
-- Nobody can read these tables directly: everything goes through chat_pulse(), which answers only for chats the person belongs to.
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.user_presence (
  user_id   uuid primary key references auth.users(id) on delete cascade,
  last_seen timestamptz not null default now()
);
create table if not exists public.chat_typing (
  link_id uuid not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  at      timestamptz not null default now(),
  primary key (link_id, user_id)
);
alter table public.user_presence enable row level security;
alter table public.chat_typing   enable row level security;
revoke all on public.user_presence, public.chat_typing from anon, authenticated;

-- one call does it all: marks me as active, sets/clears my "typing" flag in this chat, and returns what the others are doing
--   p_link   = the open chat (null = I am in the app but not in a chat)
--   p_typing = true while I am writing
-- answer: { ok, typing: [user ids typing right now (not me)], seen: { user id: seconds since that person was last active } }
create or replace function public.chat_pulse(p_link uuid default null, p_typing boolean default false)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); ty json := '[]'::json; sn json := '{}'::json; peers uuid[];
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  insert into public.user_presence (user_id, last_seen) values (me, now())
    on conflict (user_id) do update set last_seen = now();
  if p_link is null or not public.chat_member(p_link::text) then
    return json_build_object('ok', true, 'typing', ty, 'seen', sn);
  end if;
  if p_typing then
    insert into public.chat_typing (link_id, user_id, at) values (p_link, me, now())
      on conflict (link_id, user_id) do update set at = now();
  else
    delete from public.chat_typing where link_id = p_link and user_id = me;
  end if;
  select array_agg(u) into peers from (
    select counselor_id as u from public.links where id = p_link
    union select student_id from public.links where id = p_link
    union select owner_id from public.friend_links where id = p_link
    union select friend_id from public.friend_links where id = p_link
    union select user_id from public.chat_group_members where group_id = p_link
  ) x where u is not null and u <> me;
  select coalesce(json_agg(user_id), '[]'::json) into ty
    from public.chat_typing where link_id = p_link and user_id <> me and at > now() - interval '6 seconds';
  select coalesce(json_object_agg(user_id::text, greatest(0, extract(epoch from (now() - last_seen))::int)), '{}'::json) into sn
    from public.user_presence where user_id = any(coalesce(peers, '{}'::uuid[]));
  return json_build_object('ok', true, 'typing', ty, 'seen', sn);
end $$;
revoke all on function public.chat_pulse(uuid, boolean) from public, anon;
grant execute on function public.chat_pulse(uuid, boolean) to authenticated;

-- tidy up: typing rows older than a minute are useless (optional; call from a scheduled job if you like)
create or replace function public.chat_typing_cleanup() returns void language sql security definer set search_path = public as $$
  delete from public.chat_typing where at < now() - interval '1 minute';
$$;
revoke all on function public.chat_typing_cleanup() from public, anon, authenticated;
notify pgrst, 'reload schema';


-- =====================================================================
-- v312: hide my "online / last seen / typing". With p_hide = true nothing about me is recorded or shown to others
-- (and I do not see it for others either, like Telegram). Run this block once (it is safe to run again).
-- =====================================================================
alter table public.user_presence add column if not exists hidden boolean not null default false;
drop function if exists public.chat_pulse(uuid, boolean);
create or replace function public.chat_pulse(p_link uuid default null, p_typing boolean default false, p_hide boolean default false)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); ty json := '[]'::json; sn json := '{}'::json; peers uuid[];
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_hide then
    insert into public.user_presence (user_id, hidden) values (me, true)
      on conflict (user_id) do update set hidden = true;
    delete from public.chat_typing where user_id = me;
    return json_build_object('ok', true, 'typing', ty, 'seen', sn, 'hidden', true);
  end if;
  insert into public.user_presence (user_id, last_seen, hidden) values (me, now(), false)
    on conflict (user_id) do update set last_seen = now(), hidden = false;
  if p_link is null or not public.chat_member(p_link::text) then
    return json_build_object('ok', true, 'typing', ty, 'seen', sn);
  end if;
  if p_typing then
    insert into public.chat_typing (link_id, user_id, at) values (p_link, me, now())
      on conflict (link_id, user_id) do update set at = now();
  else
    delete from public.chat_typing where link_id = p_link and user_id = me;
  end if;
  select array_agg(u) into peers from (
    select counselor_id as u from public.links where id = p_link
    union select student_id from public.links where id = p_link
    union select owner_id from public.friend_links where id = p_link
    union select friend_id from public.friend_links where id = p_link
    union select user_id from public.chat_group_members where group_id = p_link
  ) x where u is not null and u <> me;
  select coalesce(json_agg(t.user_id), '[]'::json) into ty
    from public.chat_typing t join public.user_presence up on up.user_id = t.user_id and not up.hidden
    where t.link_id = p_link and t.user_id <> me and t.at > now() - interval '6 seconds';
  select coalesce(json_object_agg(user_id::text, greatest(0, extract(epoch from (now() - last_seen))::int)), '{}'::json) into sn
    from public.user_presence where user_id = any(coalesce(peers, '{}'::uuid[])) and not hidden;
  return json_build_object('ok', true, 'typing', ty, 'seen', sn);
end $$;
revoke all on function public.chat_pulse(uuid, boolean, boolean) from public, anon;
grant execute on function public.chat_pulse(uuid, boolean, boolean) to authenticated;
notify pgrst, 'reload schema';


-- =====================================================================
-- v313: stickers in chats (message kind 's') + study challenge between friends (today's study minutes travel with chat_pulse).
-- Run this block once (it is safe to run again).
-- =====================================================================
alter table public.link_messages drop constraint if exists link_messages_kind_check;
alter table public.link_messages add constraint link_messages_kind_check check (kind in ('t','p','v','a','r','f','g','q','s'));

alter table public.user_presence add column if not exists study_day text;
alter table public.user_presence add column if not exists study_min int;

drop function if exists public.chat_pulse(uuid, boolean, boolean);
create or replace function public.chat_pulse(p_link uuid default null, p_typing boolean default false, p_hide boolean default false, p_study int default null, p_day text default null)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); ty json := '[]'::json; sn json := '{}'::json; st json := '{}'::json; peers uuid[];
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_hide then
    insert into public.user_presence (user_id, hidden) values (me, true)
      on conflict (user_id) do update set hidden = true;
    delete from public.chat_typing where user_id = me;
    return json_build_object('ok', true, 'typing', ty, 'seen', sn, 'study', st, 'hidden', true);
  end if;
  insert into public.user_presence (user_id, last_seen, hidden) values (me, now(), false)
    on conflict (user_id) do update set last_seen = now(), hidden = false;
  if p_study is not null and p_day ~ '^\d{4}-\d{2}-\d{2}$' then
    update public.user_presence set study_day = p_day, study_min = least(greatest(p_study, 0), 1440) where user_id = me;
  end if;
  if p_link is null or not public.chat_member(p_link::text) then
    return json_build_object('ok', true, 'typing', ty, 'seen', sn, 'study', st);
  end if;
  if p_typing then
    insert into public.chat_typing (link_id, user_id, at) values (p_link, me, now())
      on conflict (link_id, user_id) do update set at = now();
  else
    delete from public.chat_typing where link_id = p_link and user_id = me;
  end if;
  select array_agg(u) into peers from (
    select counselor_id as u from public.links where id = p_link
    union select student_id from public.links where id = p_link
    union select owner_id from public.friend_links where id = p_link
    union select friend_id from public.friend_links where id = p_link
    union select user_id from public.chat_group_members where group_id = p_link
  ) x where u is not null and u <> me;
  select coalesce(json_agg(t.user_id), '[]'::json) into ty
    from public.chat_typing t join public.user_presence up on up.user_id = t.user_id and not up.hidden
    where t.link_id = p_link and t.user_id <> me and t.at > now() - interval '6 seconds';
  select coalesce(json_object_agg(user_id::text, greatest(0, extract(epoch from (now() - last_seen))::int)), '{}'::json) into sn
    from public.user_presence where user_id = any(coalesce(peers, '{}'::uuid[])) and not hidden;
  if p_day is not null and exists (select 1 from public.friend_links where id = p_link) then
    select coalesce(json_object_agg(user_id::text, coalesce(study_min, 0)), '{}'::json) into st
      from public.user_presence where user_id = any(coalesce(peers, '{}'::uuid[])) and not hidden and study_day = p_day;
  end if;
  return json_build_object('ok', true, 'typing', ty, 'seen', sn, 'study', st);
end $$;
revoke all on function public.chat_pulse(uuid, boolean, boolean, int, text) from public, anon;
grant execute on function public.chat_pulse(uuid, boolean, boolean, int, text) to authenticated;
notify pgrst, 'reload schema';

-- =====================================================================
-- v319: friend stories (24 hours). A story is one picture (private bucket "stories", path <user id>/<story id>.jpg) plus a tiny
-- thumbnail kept in the row, so the circles above the chat search box appear at once. Only the owner and the owner's linked friends can see it.
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.friend_stories (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  media      text not null,
  thumb      text not null default '' check (char_length(thumb) <= 24000),
  caption    text not null default '' check (char_length(caption) <= 200),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '24 hours')
);
create index if not exists friend_stories_user_idx on public.friend_stories (user_id, created_at);
alter table public.friend_stories enable row level security;
drop policy if exists "fs_select" on public.friend_stories;
drop policy if exists "fs_insert" on public.friend_stories;
drop policy if exists "fs_delete" on public.friend_stories;
-- my own rows stay visible after they expire (so my phone can tidy them up); friends see a story only while it is alive
create policy "fs_select" on public.friend_stories for select using (user_id = auth.uid() or (expires_at > now() and public.friend_of(user_id)));
create policy "fs_insert" on public.friend_stories for insert with check (user_id = auth.uid());
create policy "fs_delete" on public.friend_stories for delete using (user_id = auth.uid());
revoke all on public.friend_stories from anon;
revoke insert, update on public.friend_stories from authenticated;
grant select, delete on public.friend_stories to authenticated;
grant insert (id, user_id, media, thumb, caption) on public.friend_stories to authenticated;   -- created_at / expires_at can not be forged

-- "may I see the files of this folder?" (the folder name is the owner's user id)
create or replace function public.story_visible(p_folder text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare u uuid;
begin
  begin u := p_folder::uuid; exception when others then return false; end;
  return u = auth.uid() or public.friend_of(u);
end $$;
revoke all on function public.story_visible(text) from public, anon;
grant execute on function public.story_visible(text) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit)
values ('stories', 'stories', false, 5242880)
on conflict (id) do update set public = false, file_size_limit = 5242880;
drop policy if exists "stories_obj_select" on storage.objects;
drop policy if exists "stories_obj_insert" on storage.objects;
drop policy if exists "stories_obj_delete" on storage.objects;
create policy "stories_obj_select" on storage.objects for select to authenticated using (
  bucket_id = 'stories' and public.story_visible((storage.foldername(name))[1]));
create policy "stories_obj_insert" on storage.objects for insert to authenticated with check (
  bucket_id = 'stories' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "stories_obj_delete" on storage.objects for delete to authenticated using (
  bucket_id = 'stories' and (storage.foldername(name))[1] = auth.uid()::text);
notify pgrst, 'reload schema';

-- =====================================================================
-- v320: stories - videos (up to 30 s / 40 MB), "who saw it" and the choice to allow replies (replies themselves are normal chat messages).
-- Run this block once after the v319 block (it is safe to run again).
-- =====================================================================
alter table public.friend_stories add column if not exists kind text not null default 'p';
alter table public.friend_stories drop constraint if exists friend_stories_kind_check;
alter table public.friend_stories add constraint friend_stories_kind_check check (kind in ('p','v'));
alter table public.friend_stories add column if not exists dur int not null default 0 check (dur between 0 and 60);
alter table public.friend_stories add column if not exists allow_reply boolean not null default true;
grant insert (id, user_id, media, thumb, caption, kind, dur, allow_reply) on public.friend_stories to authenticated;

update storage.buckets set file_size_limit = 41943040 where id = 'stories';

create table if not exists public.friend_story_views (
  story_id  uuid not null references public.friend_stories(id) on delete cascade,
  viewer_id uuid not null references auth.users(id) on delete cascade,
  at        timestamptz not null default now(),
  primary key (story_id, viewer_id)
);
alter table public.friend_story_views enable row level security;
drop policy if exists "fsv_select" on public.friend_story_views;
-- the viewer sees their own row, the owner of the story sees everybody who watched it
create policy "fsv_select" on public.friend_story_views for select using (
  viewer_id = auth.uid() or exists (select 1 from public.friend_stories s where s.id = story_id and s.user_id = auth.uid()));
revoke all on public.friend_story_views from anon;
revoke insert, update, delete on public.friend_story_views from authenticated;
grant select on public.friend_story_views to authenticated;

create or replace function public.story_view(p_story uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into public.friend_story_views (story_id, viewer_id)
  select s.id, auth.uid() from public.friend_stories s
   where s.id = p_story and s.user_id <> auth.uid() and s.expires_at > now() and public.friend_of(s.user_id)
  on conflict do nothing;
end $$;
revoke all on function public.story_view(uuid) from public, anon;
grant execute on function public.story_view(uuid) to authenticated;
notify pgrst, 'reload schema';

-- =====================================================================
-- v323: voice stories (kind 'a'). Text stories and layouts are ordinary pictures, they need nothing new.
-- Run this block once after the v320 block (it is safe to run again).
-- =====================================================================
alter table public.friend_stories drop constraint if exists friend_stories_kind_check;
alter table public.friend_stories add constraint friend_stories_kind_check check (kind in ('p','v','a'));
notify pgrst, 'reload schema';

-- =====================================================================
-- v343: voice chat (گفتگوی صوتی) in group and friend chats, like Telegram's voice chat.
-- The sound itself goes phone-to-phone (WebRTC). Supabase only carries the small "hello" messages two phones need
-- to find each other (offer / answer / ice) and the list of who is in the voice chat. Nothing of the sound is stored.
--   voice_rooms   = who is in the voice chat of which chat right now (a person who stops answering for ~15 s drops out)
--   voice_signals = short-lived connection messages between two people (deleted as soon as they are delivered)
-- Nobody can read these tables directly: everything goes through voice_sync / voice_leave / voice_who, which answer only for chats the person belongs to.
-- Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.voice_rooms (
  link_id   uuid not null,
  user_id   uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  beat      timestamptz not null default now(),
  muted     boolean not null default false,
  primary key (link_id, user_id)
);
create table if not exists public.voice_signals (
  id         bigint generated always as identity primary key,
  link_id    uuid not null,
  from_user  uuid not null references auth.users(id) on delete cascade,
  to_user    uuid not null references auth.users(id) on delete cascade,
  kind       text not null check (kind in ('offer','answer','ice')),
  payload    text not null check (char_length(payload) <= 16000),
  created_at timestamptz not null default now()
);
create index if not exists voice_signals_to_idx on public.voice_signals (to_user, link_id, id);
alter table public.voice_rooms   enable row level security;
alter table public.voice_signals enable row level security;
revoke all on public.voice_rooms, public.voice_signals from anon, authenticated;

-- one call does it all (called about once a second while I am in the voice chat):
--   p_join  = true on the very first call (I enter); p_muted = my microphone is off
--   p_out   = connection messages for others: [{ "to": user id, "kind": "offer|answer|ice", "payload": "..." }]
-- answer: { ok, peers: [{ user_id, muted }] (everyone in the voice chat, me included), signals: [{ from, kind, payload }] for me, dropped: true if I had been dropped }
create or replace function public.voice_sync(p_link uuid, p_join boolean default false, p_muted boolean default false, p_out jsonb default '[]'::jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); o jsonb; tgt uuid; k text; n int; pe json; sg json;
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  delete from public.voice_rooms   where link_id = p_link and beat < now() - interval '15 seconds';
  delete from public.voice_signals where created_at < now() - interval '90 seconds';
  if p_join then
    select count(*) into n from public.voice_rooms where link_id = p_link and user_id <> me;
    if n >= 7 then return json_build_object('ok', false, 'err', 'full'); end if;   -- up to 8 people (every phone talks to every other phone)
    insert into public.voice_rooms (link_id, user_id, joined_at, beat, muted) values (p_link, me, now(), now(), coalesce(p_muted, false))
      on conflict (link_id, user_id) do update set beat = now(), muted = coalesce(p_muted, false);
  else
    update public.voice_rooms set beat = now(), muted = coalesce(p_muted, false) where link_id = p_link and user_id = me;
    if not found then return json_build_object('ok', true, 'dropped', true); end if;
  end if;
  if jsonb_typeof(p_out) = 'array' then
    for o in select * from jsonb_array_elements(p_out) limit 60 loop
      begin tgt := (o->>'to')::uuid; exception when others then tgt := null; end;
      k := o->>'kind';
      if tgt is not null and tgt <> me and k in ('offer','answer','ice') and char_length(coalesce(o->>'payload','')) between 1 and 16000
         and exists (select 1 from public.voice_rooms r where r.link_id = p_link and r.user_id = tgt) then
        insert into public.voice_signals (link_id, from_user, to_user, kind, payload) values (p_link, me, tgt, k, o->>'payload');
      end if;
    end loop;
  end if;
  select coalesce(json_agg(json_build_object('user_id', r.user_id, 'muted', r.muted) order by r.joined_at), '[]'::json) into pe
    from public.voice_rooms r where r.link_id = p_link;
  with d as (delete from public.voice_signals s where s.to_user = me and s.link_id = p_link returning s.id, s.from_user, s.kind, s.payload)
    select coalesce(json_agg(json_build_object('from', d.from_user, 'kind', d.kind, 'payload', d.payload) order by d.id), '[]'::json) into sg from d;
  return json_build_object('ok', true, 'peers', pe, 'signals', sg);
end $$;

create or replace function public.voice_leave(p_link uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  delete from public.voice_rooms   where link_id = p_link and user_id = me;
  delete from public.voice_signals where link_id = p_link and (from_user = me or to_user = me);
  return json_build_object('ok', true);
end $$;

-- who is in the voice chat of this chat right now (for the «گفتگوی صوتی فعال است» bar in the chat)
create or replace function public.voice_who(p_link uuid)
returns json language plpgsql security definer set search_path = public as $$
declare u json;
begin
  if auth.uid() is null or p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false); end if;
  select coalesce(json_agg(r.user_id order by r.joined_at), '[]'::json) into u
    from public.voice_rooms r where r.link_id = p_link and r.beat > now() - interval '15 seconds';
  return json_build_object('ok', true, 'users', u);
end $$;

revoke all on function public.voice_sync(uuid, boolean, boolean, jsonb), public.voice_leave(uuid), public.voice_who(uuid) from public, anon;
grant execute on function public.voice_sync(uuid, boolean, boolean, jsonb), public.voice_leave(uuid), public.voice_who(uuid) to authenticated;
notify pgrst, 'reload schema';

-- =====================================================================
-- v344: voice chat like Telegram's — a title everybody sees («ویرایش عنوان») and «پایان گفتگوی صوتی» for everyone.
--   voice_titles = the title of the voice chat while it is running (removed when the last person leaves)
--   voice_ends   = «this voice chat was ended by X» for a minute, so the others are told why they were dropped
-- voice_sync / voice_who now also answer with the title (older apps simply ignore the extra fields).
-- Ending: in a group only the owner can end it for everyone; in a friend chat both friends can.
-- Run this block once (it is safe to run again; it needs the v343 block first).
-- =====================================================================
create table if not exists public.voice_titles (
  link_id    uuid primary key,
  title      text not null check (char_length(title) between 1 and 60),
  updated_at timestamptz not null default now()
);
create table if not exists public.voice_ends (
  link_id  uuid primary key,
  by_user  uuid,
  ended_at timestamptz not null default now()
);
alter table public.voice_titles enable row level security;
alter table public.voice_ends   enable row level security;
revoke all on public.voice_titles, public.voice_ends from anon, authenticated;

create or replace function public.voice_sync(p_link uuid, p_join boolean default false, p_muted boolean default false, p_out jsonb default '[]'::jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); o jsonb; tgt uuid; k text; n int; pe json; sg json; ti text; en boolean;
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  delete from public.voice_rooms   where link_id = p_link and beat < now() - interval '15 seconds';
  delete from public.voice_signals where created_at < now() - interval '90 seconds';
  if not exists (select 1 from public.voice_rooms where link_id = p_link) then
    delete from public.voice_titles where link_id = p_link;   -- nobody is in: the old title is gone
  end if;
  if p_join then
    select count(*) into n from public.voice_rooms where link_id = p_link and user_id <> me;
    if n >= 7 then return json_build_object('ok', false, 'err', 'full'); end if;
    if n = 0 then delete from public.voice_ends where link_id = p_link; end if;   -- a fresh voice chat starts
    insert into public.voice_rooms (link_id, user_id, joined_at, beat, muted) values (p_link, me, now(), now(), coalesce(p_muted, false))
      on conflict (link_id, user_id) do update set beat = now(), muted = coalesce(p_muted, false);
  else
    update public.voice_rooms set beat = now(), muted = coalesce(p_muted, false) where link_id = p_link and user_id = me;
    if not found then
      select exists (select 1 from public.voice_ends e where e.link_id = p_link and e.ended_at > now() - interval '60 seconds') into en;
      return json_build_object('ok', true, 'dropped', true, 'ended', coalesce(en, false));
    end if;
  end if;
  if jsonb_typeof(p_out) = 'array' then
    for o in select * from jsonb_array_elements(p_out) limit 60 loop
      begin tgt := (o->>'to')::uuid; exception when others then tgt := null; end;
      k := o->>'kind';
      if tgt is not null and tgt <> me and k in ('offer','answer','ice') and char_length(coalesce(o->>'payload','')) between 1 and 16000
         and exists (select 1 from public.voice_rooms r where r.link_id = p_link and r.user_id = tgt) then
        insert into public.voice_signals (link_id, from_user, to_user, kind, payload) values (p_link, me, tgt, k, o->>'payload');
      end if;
    end loop;
  end if;
  select coalesce(json_agg(json_build_object('user_id', r.user_id, 'muted', r.muted) order by r.joined_at), '[]'::json) into pe
    from public.voice_rooms r where r.link_id = p_link;
  with d as (delete from public.voice_signals s where s.to_user = me and s.link_id = p_link returning s.id, s.from_user, s.kind, s.payload)
    select coalesce(json_agg(json_build_object('from', d.from_user, 'kind', d.kind, 'payload', d.payload) order by d.id), '[]'::json) into sg from d;
  select t.title into ti from public.voice_titles t where t.link_id = p_link;
  return json_build_object('ok', true, 'peers', pe, 'signals', sg, 'title', ti);
end $$;

create or replace function public.voice_who(p_link uuid)
returns json language plpgsql security definer set search_path = public as $$
declare u json; ti text;
begin
  if auth.uid() is null or p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false); end if;
  select coalesce(json_agg(r.user_id order by r.joined_at), '[]'::json) into u
    from public.voice_rooms r where r.link_id = p_link and r.beat > now() - interval '15 seconds';
  select t.title into ti from public.voice_titles t where t.link_id = p_link;
  return json_build_object('ok', true, 'users', u, 'title', ti);
end $$;

-- change (or clear, with an empty text) the title; only someone who is in the voice chat can
create or replace function public.voice_title(p_link uuid, p_title text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); t text := left(btrim(coalesce(p_title, '')), 60);
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  if not exists (select 1 from public.voice_rooms r where r.link_id = p_link and r.user_id = me) then return json_build_object('ok', false, 'err', 'notin'); end if;
  if t = '' then delete from public.voice_titles where link_id = p_link;
  else insert into public.voice_titles (link_id, title) values (p_link, t) on conflict (link_id) do update set title = excluded.title, updated_at = now(); end if;
  return json_build_object('ok', true, 'title', nullif(t, ''));
end $$;

-- end the voice chat for everybody
create or replace function public.voice_end(p_link uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  if exists (select 1 from public.chat_groups g where g.id = p_link and g.owner_id <> me) then return json_build_object('ok', false, 'err', 'owner'); end if;
  insert into public.voice_ends (link_id, by_user) values (p_link, me)
    on conflict (link_id) do update set by_user = excluded.by_user, ended_at = now();
  delete from public.voice_rooms   where link_id = p_link;
  delete from public.voice_signals where link_id = p_link;
  delete from public.voice_titles  where link_id = p_link;
  return json_build_object('ok', true);
end $$;

revoke all on function public.voice_sync(uuid, boolean, boolean, jsonb), public.voice_who(uuid), public.voice_title(uuid, text), public.voice_end(uuid) from public, anon;
grant execute on function public.voice_sync(uuid, boolean, boolean, jsonb), public.voice_who(uuid), public.voice_title(uuid, text), public.voice_end(uuid) to authenticated;
notify pgrst, 'reload schema';


-- =====================================================================
-- v345: group description (shown on the new group info page, like Telegram's «Description»).
-- Only the owner of the group can change it. Run this block once (it is safe to run again).
-- =====================================================================
alter table public.chat_groups add column if not exists description text not null default '';

create or replace function public.group_set_desc(p_group uuid, p_desc text)
returns json language plpgsql security definer set search_path = public as $$
declare d text := left(coalesce(trim(p_desc), ''), 255);
begin
  update public.chat_groups set description = d where id = p_group and owner_id = auth.uid();
  if not found then return json_build_object('ok', false, 'err', 'owner'); end if;
  return json_build_object('ok', true);
end $$;
revoke all on function public.group_set_desc(uuid, text) from public, anon;
grant execute on function public.group_set_desc(uuid, text) to authenticated;


-- =====================================================================
-- v350: group admins. The owner (مدیر) can make other members admins (is_admin). The owner and the admins can change the group's
-- name, photo and description and add members; admins can also remove ordinary members (not the owner, not other admins).
-- Only the owner can add / remove admins. Run this block once (it is safe to run again).
-- =====================================================================
alter table public.chat_group_members add column if not exists is_admin boolean not null default false;

create or replace function public.group_can_edit(p_group uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.chat_groups where id = p_group and owner_id = auth.uid())
      or exists (select 1 from public.chat_group_members where group_id = p_group and user_id = auth.uid() and is_admin);
$$;
revoke all on function public.group_can_edit(uuid) from public, anon;
grant execute on function public.group_can_edit(uuid) to authenticated;

create or replace function public.group_set_admin(p_group uuid, p_user uuid, p_on boolean)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.chat_groups where id = p_group and owner_id = auth.uid()) then return json_build_object('ok', false, 'err', 'owner'); end if;
  if exists (select 1 from public.chat_groups where id = p_group and owner_id = p_user) then return json_build_object('ok', false, 'err', 'self'); end if;
  update public.chat_group_members set is_admin = coalesce(p_on, false) where group_id = p_group and user_id = p_user;
  if not found then return json_build_object('ok', false, 'err', 'member'); end if;
  return json_build_object('ok', true);
end $$;
revoke all on function public.group_set_admin(uuid, uuid, boolean) from public, anon;
grant execute on function public.group_set_admin(uuid, uuid, boolean) to authenticated;

create or replace function public.group_add(p_group uuid, p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not public.group_can_edit(p_group) then return json_build_object('ok', false, 'err', 'owner'); end if;
  if not public.friend_of(p_user) then return json_build_object('ok', false, 'err', 'notfriend'); end if;
  if exists (select 1 from public.chat_group_members where group_id = p_group and user_id = p_user) then return json_build_object('ok', true); end if;
  if (select count(*) from public.chat_group_members where group_id = p_group) >= 50 then return json_build_object('ok', false, 'err', 'full'); end if;
  insert into public.chat_group_members (group_id, user_id, name) values (p_group, p_user, coalesce(public.friend_name_of(p_user), ''));
  return json_build_object('ok', true);
end $$;

create or replace function public.group_remove(p_group uuid, p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
declare is_owner boolean;
begin
  if not public.group_can_edit(p_group) then return json_build_object('ok', false, 'err', 'owner'); end if;
  if p_user = auth.uid() then return json_build_object('ok', false, 'err', 'self'); end if;
  select exists (select 1 from public.chat_groups where id = p_group and owner_id = auth.uid()) into is_owner;
  if exists (select 1 from public.chat_groups where id = p_group and owner_id = p_user) then return json_build_object('ok', false, 'err', 'owner'); end if;
  if not is_owner and exists (select 1 from public.chat_group_members where group_id = p_group and user_id = p_user and is_admin) then
    return json_build_object('ok', false, 'err', 'admin');
  end if;
  delete from public.chat_group_members where group_id = p_group and user_id = p_user;
  return json_build_object('ok', true);
end $$;

create or replace function public.group_rename(p_group uuid, p_name text)
returns json language plpgsql security definer set search_path = public as $$
declare nm text := left(trim(coalesce(p_name, '')), 60);
begin
  if nm = '' then return json_build_object('ok', false, 'err', 'name'); end if;
  if not public.group_can_edit(p_group) then return json_build_object('ok', false, 'err', 'owner'); end if;
  update public.chat_groups set name = nm where id = p_group;
  return json_build_object('ok', true);
end $$;

create or replace function public.group_set_desc(p_group uuid, p_desc text)
returns json language plpgsql security definer set search_path = public as $$
declare d text := left(coalesce(trim(p_desc), ''), 255);
begin
  if not public.group_can_edit(p_group) then return json_build_object('ok', false, 'err', 'owner'); end if;
  update public.chat_groups set description = d where id = p_group;
  return json_build_object('ok', true);
end $$;

create or replace function public.group_set_photo(p_group uuid, p_photo text)
returns json language plpgsql security definer set search_path = public as $$
declare ph text := coalesce(trim(p_photo), '');
begin
  if ph <> '' and ph !~ '^av([1-9]|1[0-2])$'
     and not (ph ~ '^data:image/jpeg;base64,[A-Za-z0-9+/=]+$' and length(ph) <= 24000) then
    return json_build_object('ok', false, 'err', 'photo');
  end if;
  if not public.group_can_edit(p_group) then return json_build_object('ok', false, 'err', 'owner'); end if;
  update public.chat_groups set photo = ph where id = p_group;
  return json_build_object('ok', true);
end $$;
notify pgrst, 'reload schema';


-- =====================================================================
-- v367: voice chat — messages inside the voice chat, and a host.
--   * messages: everybody in the voice chat can write; they live only as long as the voice chat (deleted when it ends / the last person leaves)
--   * host: the person who STARTED the voice chat. Only the host can remove someone, close someone's microphone, and end the voice chat for everybody.
--   voice_hosts = who started the voice chat (kept while it is running)
--   voice_kicks = people the host removed (they cannot come back while it is running)
--   voice_msgs  = the messages of the voice chat
-- Older apps keep working (they ignore the new fields). Run this block once (it is safe to run again; it needs the v343 and v344 blocks first).
-- =====================================================================
alter table public.voice_rooms add column if not exists forced boolean not null default false;   -- the host closed this person's microphone
create table if not exists public.voice_hosts (
  link_id    uuid primary key,
  host_id    uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table if not exists public.voice_kicks (
  link_id   uuid not null,
  user_id   uuid not null references auth.users(id) on delete cascade,
  kicked_at timestamptz not null default now(),
  primary key (link_id, user_id)
);
create table if not exists public.voice_msgs (
  id         bigint generated always as identity primary key,
  link_id    uuid not null,
  from_user  uuid not null references auth.users(id) on delete cascade,
  body       text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now()
);
create index if not exists voice_msgs_idx on public.voice_msgs (link_id, id);
alter table public.voice_hosts enable row level security;
alter table public.voice_kicks enable row level security;
alter table public.voice_msgs  enable row level security;
revoke all on public.voice_hosts, public.voice_kicks, public.voice_msgs from anon, authenticated;

-- nobody is in the voice chat any more: everything that belonged to it goes (title, host, removed people, messages)
create or replace function public.voice_clean(p_link uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  delete from public.voice_rooms   where link_id = p_link and beat < now() - interval '15 seconds';
  delete from public.voice_signals where created_at < now() - interval '90 seconds';
  if not exists (select 1 from public.voice_rooms where link_id = p_link) then
    delete from public.voice_titles where link_id = p_link;
    delete from public.voice_hosts  where link_id = p_link;
    delete from public.voice_kicks  where link_id = p_link;
    delete from public.voice_msgs   where link_id = p_link;
  end if;
end $$;

drop function if exists public.voice_sync(uuid, boolean, boolean, jsonb);
create or replace function public.voice_sync(p_link uuid, p_join boolean default false, p_muted boolean default false, p_out jsonb default '[]'::jsonb, p_msg text default null, p_since bigint default 0)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); o jsonb; tgt uuid; k text; n int; pe json; sg json; ti text; en boolean; ho uuid; ms json; m text := left(btrim(coalesce(p_msg, '')), 500); kk boolean;
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  perform public.voice_clean(p_link);
  if p_join then
    select count(*) into n from public.voice_rooms where link_id = p_link and user_id <> me;
    if n >= 7 then return json_build_object('ok', false, 'err', 'full'); end if;
    if n > 0 and exists (select 1 from public.voice_kicks x where x.link_id = p_link and x.user_id = me) then return json_build_object('ok', false, 'err', 'kicked'); end if;
    if n = 0 then
      delete from public.voice_ends  where link_id = p_link;   -- a fresh voice chat starts, and I am its host
      delete from public.voice_kicks where link_id = p_link;
      delete from public.voice_msgs  where link_id = p_link;
      insert into public.voice_hosts (link_id, host_id) values (p_link, me) on conflict (link_id) do update set host_id = excluded.host_id, created_at = now();
    end if;
    insert into public.voice_rooms (link_id, user_id, joined_at, beat, muted) values (p_link, me, now(), now(), coalesce(p_muted, false))
      on conflict (link_id, user_id) do update set beat = now(), muted = coalesce(p_muted, false) or public.voice_rooms.forced;
  else
    update public.voice_rooms set beat = now(), muted = (coalesce(p_muted, false) or forced) where link_id = p_link and user_id = me;
    if not found then
      select exists (select 1 from public.voice_ends e where e.link_id = p_link and e.ended_at > now() - interval '60 seconds') into en;
      select exists (select 1 from public.voice_kicks x where x.link_id = p_link and x.user_id = me) into kk;
      return json_build_object('ok', true, 'dropped', true, 'ended', coalesce(en, false), 'kicked', coalesce(kk, false));
    end if;
  end if;
  if m <> '' then
    insert into public.voice_msgs (link_id, from_user, body) values (p_link, me, m);
    delete from public.voice_msgs where link_id = p_link and id <= coalesce((select id from public.voice_msgs where link_id = p_link order by id desc offset 300 limit 1), 0);
  end if;
  if jsonb_typeof(p_out) = 'array' then
    for o in select * from jsonb_array_elements(p_out) limit 60 loop
      begin tgt := (o->>'to')::uuid; exception when others then tgt := null; end;
      k := o->>'kind';
      if tgt is not null and tgt <> me and k in ('offer','answer','ice') and char_length(coalesce(o->>'payload','')) between 1 and 16000
         and exists (select 1 from public.voice_rooms r where r.link_id = p_link and r.user_id = tgt) then
        insert into public.voice_signals (link_id, from_user, to_user, kind, payload) values (p_link, me, tgt, k, o->>'payload');
      end if;
    end loop;
  end if;
  select coalesce(json_agg(json_build_object('user_id', r.user_id, 'muted', r.muted, 'forced', r.forced) order by r.joined_at), '[]'::json) into pe
    from public.voice_rooms r where r.link_id = p_link;
  with d as (delete from public.voice_signals s where s.to_user = me and s.link_id = p_link returning s.id, s.from_user, s.kind, s.payload)
    select coalesce(json_agg(json_build_object('from', d.from_user, 'kind', d.kind, 'payload', d.payload) order by d.id), '[]'::json) into sg from d;
  select t.title into ti from public.voice_titles t where t.link_id = p_link;
  select h.host_id into ho from public.voice_hosts h where h.link_id = p_link;
  select coalesce(json_agg(json_build_object('id', x.id, 'from', x.from_user, 'body', x.body, 'at', x.created_at) order by x.id), '[]'::json) into ms
    from (select * from public.voice_msgs v where v.link_id = p_link and v.id > coalesce(p_since, 0) order by v.id limit 100) x;
  return json_build_object('ok', true, 'peers', pe, 'signals', sg, 'title', ti, 'host', ho, 'msgs', ms);
end $$;

-- leaving: when I was the last one, the messages (and the rest) go with the voice chat
create or replace function public.voice_leave(p_link uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  delete from public.voice_rooms   where link_id = p_link and user_id = me;
  delete from public.voice_signals where link_id = p_link and (from_user = me or to_user = me);
  perform public.voice_clean(p_link);
  return json_build_object('ok', true);
end $$;

-- the host removes someone from the voice chat (they cannot join again until it ends)
create or replace function public.voice_kick(p_link uuid, p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  if not exists (select 1 from public.voice_hosts h where h.link_id = p_link and h.host_id = me) then return json_build_object('ok', false, 'err', 'host'); end if;
  if p_user is null or p_user = me then return json_build_object('ok', false, 'err', 'self'); end if;
  insert into public.voice_kicks (link_id, user_id) values (p_link, p_user) on conflict (link_id, user_id) do update set kicked_at = now();
  delete from public.voice_rooms   where link_id = p_link and user_id = p_user;
  delete from public.voice_signals where link_id = p_link and (from_user = p_user or to_user = p_user);
  return json_build_object('ok', true);
end $$;

-- the host closes (or opens again) someone's microphone; the person can open it again themselves only after the host allows it
create or replace function public.voice_force_mute(p_link uuid, p_user uuid, p_on boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  if not exists (select 1 from public.voice_hosts h where h.link_id = p_link and h.host_id = me) then return json_build_object('ok', false, 'err', 'host'); end if;
  if p_user is null or p_user = me then return json_build_object('ok', false, 'err', 'self'); end if;
  update public.voice_rooms set forced = coalesce(p_on, true), muted = (muted or coalesce(p_on, true)) where link_id = p_link and user_id = p_user;
  return json_build_object('ok', true);
end $$;

-- end the voice chat for everybody: only the host (the person who started it). A voice chat started before v367 keeps the old rule.
create or replace function public.voice_end(p_link uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); ho uuid;
begin
  if me is null then return json_build_object('ok', false, 'err', 'auth'); end if;
  if p_link is null or not public.chat_member(p_link::text) then return json_build_object('ok', false, 'err', 'member'); end if;
  select h.host_id into ho from public.voice_hosts h where h.link_id = p_link;
  if ho is not null then
    if ho <> me then return json_build_object('ok', false, 'err', 'owner'); end if;
  elsif exists (select 1 from public.chat_groups g where g.id = p_link and g.owner_id <> me) then return json_build_object('ok', false, 'err', 'owner'); end if;
  insert into public.voice_ends (link_id, by_user) values (p_link, me)
    on conflict (link_id) do update set by_user = excluded.by_user, ended_at = now();
  delete from public.voice_rooms   where link_id = p_link;
  delete from public.voice_signals where link_id = p_link;
  delete from public.voice_titles  where link_id = p_link;
  delete from public.voice_hosts   where link_id = p_link;
  delete from public.voice_msgs    where link_id = p_link;
  return json_build_object('ok', true);
end $$;

revoke all on function public.voice_clean(uuid), public.voice_sync(uuid, boolean, boolean, jsonb, text, bigint), public.voice_leave(uuid), public.voice_kick(uuid, uuid), public.voice_force_mute(uuid, uuid, boolean), public.voice_end(uuid) from public, anon;
grant execute on function public.voice_sync(uuid, boolean, boolean, jsonb, text, bigint), public.voice_leave(uuid), public.voice_kick(uuid, uuid), public.voice_force_mute(uuid, uuid, boolean), public.voice_end(uuid) to authenticated;
notify pgrst, 'reload schema';

-- =====================================================================
-- v368: liking a story no longer sends a heart message. The like is kept on the viewer's row and the owner
-- sees a heart in front of the name in the viewers list. Run this block once (it is safe to run again).
-- =====================================================================
alter table public.friend_story_views add column if not exists liked boolean not null default false;

create or replace function public.story_like(p_story uuid, p_on boolean default true)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into public.friend_story_views (story_id, viewer_id, liked)
  select s.id, auth.uid(), coalesce(p_on, true) from public.friend_stories s
   where s.id = p_story and s.user_id <> auth.uid() and s.expires_at > now() and public.friend_of(s.user_id)
  on conflict (story_id, viewer_id) do update set liked = excluded.liked;
end $$;
revoke all on function public.story_like(uuid, boolean) from public, anon;
grant execute on function public.story_like(uuid, boolean) to authenticated;
notify pgrst, 'reload schema';


-- =====================================================================
-- v381: votes of the Ario Channel polls are shared, so every option can show its percent.
-- One row per person and post. Run this block once (it is safe to run again).
-- =====================================================================
create table if not exists public.channel_poll_votes (
  post_id  text not null check (char_length(post_id) between 3 and 60),
  user_id  uuid not null default auth.uid(),
  opt      int  not null check (opt between 0 and 9),
  voted_at timestamptz not null default now(),
  primary key (post_id, user_id)
);
alter table public.channel_poll_votes enable row level security;
revoke all on public.channel_poll_votes from anon, authenticated;

create or replace function public.chan_poll_vote(p_post text, p_opt int)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or p_opt is null or p_opt < 0 or p_opt > 9 or p_post is null or char_length(p_post) < 3 then
    return jsonb_build_object('ok', false);
  end if;
  insert into public.channel_poll_votes (post_id, user_id, opt) values (p_post, auth.uid(), p_opt)
  on conflict (post_id, user_id) do update set opt = excluded.opt, voted_at = now();
  return jsonb_build_object('ok', true);
end $$;

-- {counts: {post: [votes of option 0, 1, ...]}, mine: {post: my option}}
create or replace function public.chan_poll_counts()
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'counts', coalesce((select jsonb_object_agg(post_id, arr) from (
        select post_id, jsonb_agg(n order by o) arr from (
          select p.post_id, g.o, count(v.user_id)::int n
            from (select distinct post_id from public.channel_poll_votes) p
            cross join generate_series(0, 9) g(o)
            left join public.channel_poll_votes v on v.post_id = p.post_id and v.opt = g.o
           group by p.post_id, g.o) c
         group by post_id) t), '{}'::jsonb),
    'mine', coalesce((select jsonb_object_agg(post_id, opt) from public.channel_poll_votes where user_id = auth.uid()), '{}'::jsonb));
$$;
revoke all on function public.chan_poll_vote(text, int), public.chan_poll_counts() from public, anon;
grant execute on function public.chan_poll_vote(text, int), public.chan_poll_counts() to authenticated;
notify pgrst, 'reload schema';
