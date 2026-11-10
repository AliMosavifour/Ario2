package __APPID__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Chat notifications like WhatsApp / Telegram: the sender's name and picture and the text of the message
 * (friend, group and advisor chats). The background worker (ArioCounselorWorker) calls checkNew() every ~15 minutes while the app
 * is closed; messages that arrived while the app was on screen are never announced (the app shows them itself).
 * Each chat keeps its own notification that shows the last few messages (MessagingStyle).
 */
public class ArioChatNotify {
    static final String CH = "ario_msg";
    static final String PREFS = "ario_chat";
    static final long SCREEN_MS = 5 * 60 * 1000L;   // the web app pings every 4 minutes while it is open

    /** Asks Supabase for messages other people sent since the last mark and announces them. */
    static void checkNew(Context c) throws Exception {
        SharedPreferences s = ArioCounselorWorker.sp(c);
        if (!s.getBoolean("on", false)) return;
        long now = System.currentTimeMillis(), mark = s.getLong("cm", 0);
        if (now - s.getLong("fg", 0) < SCREEN_MS || mark <= 0 || !s.getBoolean("cn", true)) {
            s.edit().putLong("cm", now).apply();   // on screen / first run / switched off: nothing older is announced later
            return;
        }
        String url = s.getString("url", ""), key = s.getString("key", ""), uid = s.getString("uid", "");
        if (url.isEmpty() || key.isEmpty() || uid.isEmpty()) return;
        if (s.getLong("exp", 0) < now / 1000 + 120) {
            if (!ArioCounselorWorker.refresh(c)) return;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String since = URLEncoder.encode(f.format(new Date(mark)), "UTF-8");
        String path = "/rest/v1/link_messages?sender_id=neq." + uid + "&created_at=gt." + since
                + "&select=id,link_id,sender_id,kind,body,mname,meta,created_at,seen_at&order=created_at.asc&limit=40";
        String[] res = ArioCounselorWorker.get(url + path, key, s.getString("at", ""));
        if ("401".equals(res[0])) {
            if (!ArioCounselorWorker.refresh(c)) return;
            res = ArioCounselorWorker.get(url + path, key, s.getString("at", ""));
        }
        if (!"200".equals(res[0])) return;
        JSONArray rows = new JSONArray(res[1]);
        if (rows.length() == 0) return;

        long newest = mark;
        for (int i = 0; i < rows.length(); i++) {
            long t = ArioCounselorWorker.parseIso(rows.getJSONObject(i).optString("created_at", ""));
            if (t > newest) newest = t;
        }
        s.edit().putLong("cm", newest + 1).apply();

        Map<String, String[]> info = chatInfo(c, url, key, uid);
        String muted = "," + s.getString("mu", "") + ",";
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String link = r.optString("link_id", "");
            if (link.isEmpty() || muted.contains("," + link + ",")) continue;
            String[] inf = info.get(link);
            boolean group = inf != null && "1".equals(inf[1]);
            if (!group && !r.isNull("seen_at")) continue;
            JSONObject meta = r.optJSONObject("meta");
            if (meta == null) meta = new JSONObject();
            JSONObject av = meta.optJSONObject("av");
            String peer = inf != null && !inf[0].isEmpty() ? inf[0] : ArioLang.t(c, "پیام جدید", "New message");
            String from = group ? meta.optString("nm", "").trim() : peer;
            if (from.isEmpty()) from = ArioLang.t(c, "عضو", "Member");
            String p = av == null ? "" : av.optString("p", "");
            int hue = av == null ? hueOf(from) : av.optInt("h", hueOf(from));
            String letter = av == null ? "" : av.optString("n", "");
            if (letter.isEmpty() && from.length() > 0) letter = from.substring(0, from.offsetByCodePoints(0, 1));
            post(c, link, group ? peer : "", from, textOf(c, r), ArioCounselorWorker.parseIso(r.optString("created_at", "")), p, hue, letter);
        }
    }

    /** chat id -> {title, "1" when it is a group}. Missing tables or errors just leave the map smaller. */
    static Map<String, String[]> chatInfo(Context c, String url, String key, String uid) {
        Map<String, String[]> m = new HashMap<String, String[]>();
        String tok = ArioCounselorWorker.sp(c).getString("at", "");
        try {
            String[] r = ArioCounselorWorker.get(url + "/rest/v1/links?select=id,counselor_id,counselor_name,student_name&limit=200", key, tok);
            if ("200".equals(r[0])) {
                JSONArray a = new JSONArray(r[1]);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String n = uid.equals(o.optString("counselor_id")) ? o.optString("student_name", "") : o.optString("counselor_name", "");
                    m.put(o.optString("id"), new String[]{n, "0"});
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        try {
            String[] r = ArioCounselorWorker.get(url + "/rest/v1/friend_links?select=id,owner_id,owner_name,friend_name&limit=100", key, tok);
            if ("200".equals(r[0])) {
                JSONArray a = new JSONArray(r[1]);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String n = uid.equals(o.optString("owner_id")) ? o.optString("friend_name", "") : o.optString("owner_name", "");
                    m.put(o.optString("id"), new String[]{n, "0"});
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        try {
            String[] r = ArioCounselorWorker.get(url + "/rest/v1/chat_groups?select=id,name&limit=100", key, tok);
            if ("200".equals(r[0])) {
                JSONArray a = new JSONArray(r[1]);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String n = o.optString("name", "");
                    m.put(o.optString("id"), new String[]{n.isEmpty() ? ArioLang.t(c, "گروه", "Group") : n, "1"});
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return m;
    }

    static String textOf(Context c, JSONObject r) {
        String k = r.optString("kind", "t"), b = r.optString("body", "").trim();
        if ("t".equals(k)) {
            if (b.length() > 400) b = b.substring(0, 400) + "…";
            return b.isEmpty() ? ArioLang.t(c, "پیام جدید", "New message") : b;
        }
        if ("p".equals(k)) return "📷 " + (b.isEmpty() ? ArioLang.t(c, "عکس", "Photo") : b);
        if ("v".equals(k)) return "🎥 " + (b.isEmpty() ? ArioLang.t(c, "ویدیو", "Video") : b);
        if ("r".equals(k)) return "🎥 " + ArioLang.t(c, "پیام ویدیویی", "Video message");
        if ("a".equals(k)) return "🎤 " + ArioLang.t(c, "پیام صوتی", "Voice message");
        if ("f".equals(k)) {
            String n = r.optString("mname", "");
            return "📎 " + (n.isEmpty() ? ArioLang.t(c, "فایل", "File") : n);
        }
        if ("g".equals(k)) return "📝 " + ArioLang.t(c, "گزارش روزانه", "Daily report");
        if ("s".equals(k)) return "🙂 " + ArioLang.t(c, "استیکر", "Sticker");
        if ("q".equals(k)) return "📊 " + ArioLang.t(c, "نظرسنجی", "Poll");
        return b.isEmpty() ? ArioLang.t(c, "پیام جدید", "New message") : b;
    }

    static int hueOf(String n) {
        int h = 0;
        for (int i = 0; i < n.length(); i++) h = (h * 31 + n.charAt(i)) % 360;
        return h;
    }

    /** Round picture: the built-in photo (av1…av12) when the sender has one, otherwise a colour disc with the first letter. */
    static Bitmap avatar(Context c, String p, int hue, String letter) {
        int S = 128;
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap src = null;
        if (p != null && p.matches("av([1-9]|1[0-2])")) {
            try {
                InputStream in = c.getAssets().open("public/img/" + p + ".webp");
                Bitmap raw = BitmapFactory.decodeStream(in);
                in.close();
                if (raw != null) src = Bitmap.createScaledBitmap(raw, S, S, true);
            } catch (Throwable t) {
                src = null;
            }
        }
        if (src != null) {
            pt.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            cv.drawCircle(S / 2f, S / 2f, S / 2f, pt);
        } else {
            pt.setColor(Color.HSVToColor(new float[]{((hue % 360) + 360) % 360, 0.55f, 0.62f}));
            cv.drawCircle(S / 2f, S / 2f, S / 2f, pt);
            pt.setColor(Color.WHITE);
            pt.setTextSize(S * 0.5f);
            pt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(letter == null || letter.isEmpty() ? "؟" : letter, S / 2f, S / 2f - (pt.descent() + pt.ascent()) / 2f, pt);
        }
        return out;
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, ArioLang.t(c, "پیام‌های گفتگو", "Chat messages"), NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(ArioLang.t(c, "پیام دوستان، گروه‌ها و مشاور", "Messages from friends, groups and your advisor"));
            ch.enableVibration(true);
            ch.enableLights(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** One notification per chat; it keeps the last few messages so a burst reads like a conversation. */
    static void post(Context c, String link, String groupTitle, String from, String text, long when, String p, int hue, String letter) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            int id = 2100001000 + (Math.abs(link.hashCode()) % 90000);
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray hist;
            try {
                hist = new JSONArray(sp.getString("h_" + link, "[]"));
            } catch (Throwable t) {
                hist = new JSONArray();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean shown = false;
                for (StatusBarNotification n : nm.getActiveNotifications()) if (n.getId() == id) shown = true;
                if (!shown) hist = new JSONArray();   // the earlier ones were read or swiped away
            }
            JSONObject h = new JSONObject();
            h.put("n", from).put("t", text).put("w", when > 0 ? when : System.currentTimeMillis()).put("p", p).put("h", hue).put("l", letter);
            hist.put(h);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, hist.length() - 6); i < hist.length(); i++) keep.put(hist.get(i));
            sp.edit().putString("h_" + link, keep.toString()).apply();

            Person me = new Person.Builder().setName(ArioLang.t(c, "من", "Me")).build();
            NotificationCompat.MessagingStyle st = new NotificationCompat.MessagingStyle(me);
            boolean group = !groupTitle.isEmpty();
            if (group) {
                st.setConversationTitle(groupTitle);
                st.setGroupConversation(true);
            }
            Bitmap last = null;
            for (int i = 0; i < keep.length(); i++) {
                JSONObject o = keep.getJSONObject(i);
                Bitmap bm = avatar(c, o.optString("p", ""), o.optInt("h", 0), o.optString("l", ""));
                last = bm;
                Person sender = new Person.Builder().setName(o.optString("n", "")).setKey(o.optString("n", "")).setIcon(IconCompat.createWithBitmap(bm)).build();
                st.addMessage(o.optString("t", ""), o.optLong("w", System.currentTimeMillis()), sender);
            }
            int small = ArioNotifyCore.iconRes(c, "ic_stat_adv");
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(small != 0 ? small : android.R.drawable.ic_dialog_email)
                    .setColor(Color.parseColor("#f47b3a"))
                    .setContentTitle(group ? groupTitle : from)
                    .setContentText(group ? from + ": " + text : text)
                    .setStyle(st)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setWhen(when > 0 ? when : System.currentTimeMillis())
                    .setShowWhen(true)
                    .setContentIntent(ArioNotifyCore.openPi(c, id, "chat:" + link));
            if (last != null) b.setLargeIcon(last);
            nm.notify(id, b.build());
        } catch (Throwable t) {
            // never crash the worker
        }
    }
}
