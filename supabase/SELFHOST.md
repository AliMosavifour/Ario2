# Running Ario's server inside Iran (self-hosted Supabase)

Why: the app's cloud features (friend chat, groups, advisor ↔ student, sync) talk to Supabase.
If the international internet is cut, `*.supabase.co` is unreachable. A copy of Supabase on a server **inside Iran**
keeps those features working on the national network.

The app side is ready (v302): sign-in sheet → "سرور (پیشرفته)" → address + anon key → "آزمایش اتصال" → "ذخیره و استفاده".

## What you need
- A VPS in Iran (Linux, 4 GB RAM / 2 vCPU / 40 GB disk is a sensible start), a domain with a **valid HTTPS certificate**
  (the Android app only talks to `https://`), and root/SSH access.
- Docker + Docker Compose. (If Docker Hub is blocked from the server, use an Iranian registry mirror and pre-pull the images.)

## 1. Install Supabase (official self-hosting)
```
git clone --depth 1 https://github.com/supabase/supabase
mkdir ario-supabase && cp -r supabase/docker/* ario-supabase/ && cp supabase/docker/.env.example ario-supabase/.env
cd ario-supabase
```
Edit `.env` (never reuse the example secrets):
- `POSTGRES_PASSWORD`, `JWT_SECRET` (≥ 32 random chars)
- `ANON_KEY` and `SERVICE_ROLE_KEY` – generate them from your `JWT_SECRET` (Supabase docs: "Generate API keys").
  **Only `ANON_KEY` goes in the app. Never put `SERVICE_ROLE_KEY` in the app.**
- `SITE_URL=https://your-domain.ir`, `API_EXTERNAL_URL=https://your-domain.ir`, `SUPABASE_PUBLIC_URL=https://your-domain.ir`
- Sign-up without e-mail confirmation (until you have a domestic SMTP): `ENABLE_EMAIL_AUTOCONFIRM=true`.
  Later, to send real e-mails (confirmation / password reset) fill the `SMTP_*` values with an Iranian SMTP service.
- Strong `DASHBOARD_PASSWORD`.

Start: `docker compose up -d`  (first start downloads several images).

## 2. HTTPS
Put a reverse proxy in front of Kong (port 8000), e.g. Caddy:
```
your-domain.ir {
  reverse_proxy localhost:8000
}
```
Check: `https://your-domain.ir/auth/v1/health` answers (send header `apikey: <ANON_KEY>`).

## 3. Create the tables
Open Studio (`https://your-domain.ir` → SQL Editor, or use `psql`) and run `supabase/setup.sql` **in pieces** (the Studio editor can cut very long pasted scripts):
the blocks are marked `v271, v277, v279, v295, v296, v298, v301 …`; run them in file order. Easiest and safest from the server shell:
```
docker compose exec -T db psql -U postgres -d postgres < setup.sql
```
The script already creates the private `chat` storage bucket and the security rules (RLS). At the end it runs `notify pgrst, 'reload schema'`.

## 4. Point the app at it
Sign-in sheet → "سرور (پیشرفته)" → address `https://your-domain.ir`, key = your `ANON_KEY` → test → save.
Each person must create a new account on the new server (accounts and chats do not move from supabase.co automatically).

## Notes
- Back up Postgres every day (`pg_dump`) and keep the server updated.
- The advisor "phone notice while the app is closed" uses the same address, so it follows the server you saved.
- Pictures/videos in chats are stored in the `chat` bucket on this server (50 MB per file).
- Keep the default server as a fallback only while the international internet works; the app uses exactly one server at a time.
