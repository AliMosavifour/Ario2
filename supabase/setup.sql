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
