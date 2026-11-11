package __APPID__;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advisor (counselor) phone notice while the app is closed.
 * Every ~15 minutes Android runs this worker; it asks Supabase for the daily reports the advisor has not opened yet
 * and, when a new one arrived, posts a notification. The web app hands over the sign-in (setSession) and keeps the
 * "last notified" mark in step. When the app is on screen the worker does nothing (the app checks by itself).
 * If the worker has to renew the sign-in, the new tokens wait in "nat" until the web app takes them (takePending),
 * so the two sides never use an already-replaced refresh token.
 */
public class ArioCounselorWorker extends Worker {
    static final String PREFS = "ario_cw";
    static final String WORK = "ario_counselor_watch";
    static final int NOTIF_ID = 2100000700;
    static final long FG_QUIET_MS = 7 * 60 * 1000L;

    public ArioCounselorWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---------- called by the web app ---------- */
    static synchronized void setSession(Context c, JSONObject d) {
        SharedPreferences s = sp(c);
        boolean on = d.optBoolean("on", false);
        if (!on || d.optString("uid", "").isEmpty()) {
            s.edit().clear().apply();
            try {
                WorkManager.getInstance(c).cancelUniqueWork(WORK);
            } catch (Throwable t) {
                // ignore
            }
            return;
        }
        String uid = d.optString("uid");
        boolean same = uid.equals(s.getString("uid", ""));
        long exp = d.optLong("exp", 0);
        SharedPreferences.Editor e = s.edit();
        e.putString("url", d.optString("url")).putString("key", d.optString("key")).putString("uid", uid).putBoolean("on", true);
        // keep the tokens the worker renewed itself when they are newer than what the web app sent
        if (!same || exp >= s.getLong("exp", 0)) {
            e.putString("at", d.optString("at")).putString("rt", d.optString("rt")).putLong("exp", exp);
            if (!same) e.remove("nat");
        }
        e.putString("mu", d.optString("mu", "")).putBoolean("cn", d.optBoolean("cn", true));
        // my own name + picture, so a reply sent from a chat notification looks like any other message from me
        JSONObject me = d.optJSONObject("me");
        if (me != null) e.putString("me", me.toString());
        long rn = Math.max(d.optLong("rn", 0), same ? s.getLong("rn", 0) : 0);
        if (rn <= 0) rn = System.currentTimeMillis();   // first time: only reports that arrive from now on
        e.putLong("rn", rn).apply();
        schedule(c);
    }

    static void schedule(Context c) {
        try {
            Constraints cons = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(ArioCounselorWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(cons).build();
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Throwable t) {
            // ignore
        }
    }

    static void foreground(Context c) {
        sp(c).edit().putLong("fg", System.currentTimeMillis()).apply();
    }

    /** The app went to the background: chat messages that arrive from now on are announced. */
    static void background(Context c) {
        sp(c).edit().putLong("fg", 0).putLong("cm", System.currentTimeMillis()).apply();
    }

    static void marked(Context c, long rn) {
        SharedPreferences s = sp(c);
        if (rn > s.getLong("rn", 0)) s.edit().putLong("rn", rn).apply();
    }

    /** What the web app should adopt: tokens the worker renewed, and the newest report time already announced. */
    static synchronized JSONObject takePending(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences s = sp(c);
            o.put("rn", s.getLong("rn", 0));
            String nat = s.getString("nat", "");
            if (!nat.isEmpty()) {
                o.put("s", new JSONObject(nat));
                s.edit().remove("nat").apply();
            }
        } catch (Throwable t) {
            // ignore
        }
        return o;
    }

    /* ---------- the periodic check ---------- */
    @NonNull
    @Override
    public Result doWork() {
        try {
            check(getApplicationContext());
        } catch (Throwable t) {
            // try again next time
        }
        try {
            ArioChatNotify.checkNew(getApplicationContext());
        } catch (Throwable t) {
            // try again next time
        }
        return Result.success();
    }

    static void check(Context c) throws Exception {
        SharedPreferences s = sp(c);
        if (!s.getBoolean("on", false)) return;
        if (System.currentTimeMillis() - s.getLong("fg", 0) < FG_QUIET_MS) return;   // the app is open and checks by itself
        String url = s.getString("url", ""), key = s.getString("key", ""), uid = s.getString("uid", "");
        if (url.isEmpty() || key.isEmpty() || uid.isEmpty()) return;

        if (s.getLong("exp", 0) < System.currentTimeMillis() / 1000 + 120) {
            if (!refresh(c)) return;
        }
        String path = "/rest/v1/student_reports?counselor_id=eq." + uid + "&seen_at=is.null&select=link_id,day,sent_at&order=sent_at.desc&limit=30";
        String[] res = get(url + path, key, s.getString("at", ""));
        if ("401".equals(res[0])) {
            if (!refresh(c)) return;
            res = get(url + path, key, sp(c).getString("at", ""));
        }
        if (!"200".equals(res[0])) return;
        JSONArray rows = new JSONArray(res[1]);
        if (rows.length() == 0) return;

        long rn = s.getLong("rn", 0), newest = 0;
        int fresh = 0;
        String freshLink = "";
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            long t = parseIso(r.optString("sent_at", ""));
            if (t > newest) newest = t;
            if (t > rn) {
                fresh++;
                freshLink = r.optString("link_id", "");
            }
        }
        if (fresh == 0) return;
        s.edit().putLong("rn", Math.max(newest, rn)).apply();

        String title = ArioLang.t(c, "📝 گزارش‌کار تازه", "📝 New daily report");
        String body;
        if (rows.length() == 1) {
            body = studentName(c, url, key, uid, freshLink) + ArioLang.t(c, " · گزارش روزانه‌اش را فرستاد", " · sent the daily report");
        } else {
            title = ArioLang.t(c, "📝 گزارش‌کار تازه", "📝 New daily reports");
            body = rows.length() + ArioLang.t(c, " گزارش دیده‌نشده از دانش‌آموزانت", " unread reports from your students");
        }
        JSONObject it = new JSONObject();
        it.put("id", NOTIF_ID).put("kind", "adv").put("ch", "adv").put("title", title).put("body", body).put("big", body)
                .put("ico", "ic_stat_adv_rep").put("col", "#3b9bd9").put("go", "cd");
        ArioNotifyCore.post(c, it);
    }

    static String studentName(Context c, String url, String key, String uid, String linkId) {
        String fb = ArioLang.t(c, "دانش‌آموز", "Student");
        try {
            String[] r = get(url + "/rest/v1/links?counselor_id=eq." + uid + "&select=id,student_name&limit=200", key, sp(c).getString("at", ""));
            if (!"200".equals(r[0])) return fb;
            JSONArray a = new JSONArray(r[1]);
            for (int i = 0; i < a.length(); i++) {
                JSONObject l = a.getJSONObject(i);
                if (linkId.equals(l.optString("id"))) {
                    String n = l.optString("student_name", "");
                    return n.isEmpty() ? fb : n;
                }
            }
        } catch (Throwable t) {
            // use the plain word
        }
        return fb;
    }

    /** Renews the sign-in. The new tokens are kept for the web app too. Returns false when there is nothing usable. */
    static synchronized boolean refresh(Context c) {
        SharedPreferences s = sp(c);
        try {
            String url = s.getString("url", ""), key = s.getString("key", ""), rt = s.getString("rt", "");
            if (rt.isEmpty()) return false;
            HttpURLConnection h = (HttpURLConnection) new URL(url + "/auth/v1/token?grant_type=refresh_token").openConnection();
            h.setRequestMethod("POST");
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestProperty("apikey", key);
            h.setRequestProperty("Content-Type", "application/json");
            h.setDoOutput(true);
            OutputStream os = h.getOutputStream();
            os.write(new JSONObject().put("refresh_token", rt).toString().getBytes("UTF-8"));
            os.close();
            int code = h.getResponseCode();
            if (code == 400 || code == 401 || code == 403) {
                s.edit().putBoolean("on", false).apply();   // the sign-in ended; the web app switches it on again at the next sign-in
                return false;
            }
            if (code != 200) return false;
            JSONObject j = new JSONObject(read(h.getInputStream()));
            String at = j.optString("access_token", ""), nrt = j.optString("refresh_token", "");
            long exp = j.optLong("expires_at", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600));
            if (at.isEmpty() || nrt.isEmpty()) return false;
            JSONObject nat = new JSONObject().put("at", at).put("rt", nrt).put("exp", exp).put("uid", s.getString("uid", ""));
            s.edit().putString("at", at).putString("rt", nrt).putLong("exp", exp).putString("nat", nat.toString()).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String[] get(String url, String key, String token) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("GET");
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestProperty("apikey", key);
        h.setRequestProperty("Authorization", "Bearer " + token);
        int code = h.getResponseCode();
        String body = "";
        try {
            body = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
        } catch (Throwable t) {
            // no body
        }
        return new String[]{String.valueOf(code), body};
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)[T ](\\d\\d):(\\d\\d):(\\d\\d)(?:\\.(\\d+))?(Z|[+-]\\d\\d(?::?\\d\\d)?)?$");

    /** "2026-11-05T10:35:09.747138+00:00" -> epoch milliseconds (0 when it does not parse). */
    static long parseIso(String v) {
        try {
            Matcher m = ISO.matcher(v == null ? "" : v.trim());
            if (!m.matches()) return 0;
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            long ms = cal.getTimeInMillis();
            String f = m.group(7);
            if (f != null) {
                f = (f + "000").substring(0, 3);
                ms += Integer.parseInt(f);
            }
            String z = m.group(8);
            if (z != null && !"Z".equals(z)) {
                int sign = z.charAt(0) == '-' ? -1 : 1;
                String d = z.substring(1).replace(":", "");
                int hh = Integer.parseInt(d.substring(0, 2)), mm = d.length() >= 4 ? Integer.parseInt(d.substring(2, 4)) : 0;
                ms -= sign * (hh * 3600000L + mm * 60000L);
            }
            return ms;
        } catch (Throwable t) {
            return 0;
        }
    }
}
