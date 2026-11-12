package __APPID__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
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
import androidx.core.app.RemoteInput;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Collections;
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
            if (p.isEmpty() && av != null) p = av.optString("v", "");   // v = the sender's real profile photo (also the VIP ones), only used here
            int hue = av == null ? hueOf(from) : av.optInt("h", hueOf(from));
            String letter = av == null ? "" : av.optString("n", "");
            if (letter.isEmpty() && from.length() > 0) letter = from.substring(0, from.offsetByCodePoints(0, 1));
            String kind = inf != null && inf.length() > 2 ? inf[2] : (group ? "g" : "f");
            post(c, link, group ? peer : "", from, textOf(c, r), ArioCounselorWorker.parseIso(r.optString("created_at", "")), p, hue, letter, false, kind);
        }
    }

    /** chat id -> {title, "1" when it is a group, kind: a = advisor, f = friend, g = group}. Missing tables or errors just leave the map smaller. */
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
                    m.put(o.optString("id"), new String[]{n, "0", "a"});
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
                    m.put(o.optString("id"), new String[]{n, "0", "f"});
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
                    m.put(o.optString("id"), new String[]{n.isEmpty() ? ArioLang.t(c, "گروه", "Group") : n, "1", "g"});
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
            b = b.replaceAll("\\[\\[st:[A-Za-z0-9-]{3,40}\\]\\]", "\uD83E\uDDF8");   // v386: a sticker inside the text
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
        post(c, link, groupTitle, from, text, when, p, hue, letter, false, "");
    }

    static void post(Context c, String link, String groupTitle, String from, String text, long when, String p, int hue, String letter, boolean mine) {
        post(c, link, groupTitle, from, text, when, p, hue, letter, mine, "");
    }

    /** The round app icon (advisor chats and Ario's own messages show it instead of a person's photo). */
    static Bitmap appIcon(Context c) {
        try {
            int large = c.getResources().getIdentifier("ic_notif_large", "drawable", c.getPackageName());
            Bitmap raw = large != 0 ? BitmapFactory.decodeResource(c.getResources(), large) : null;
            if (raw == null) return null;
            int S = 128;
            Bitmap sq = Bitmap.createScaledBitmap(raw, S, S, true);
            Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pt.setShader(new BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            cv.drawCircle(S / 2f, S / 2f, S / 2f, pt);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static String firstLetter(String n) {
        return n == null || n.isEmpty() ? "؟" : n.substring(0, n.offsetByCodePoints(0, 1));
    }

    /** Android 11+: a long-lived conversation shortcut makes the system show the chat's picture as the notification's main icon (instead of only the app's coloured icon). */
    static void pushShortcut(Context c, String link, String title, Bitmap icon) {
        try {
            if (icon == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
            Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (i == null) return;
            i = new Intent(i);
            i.setAction(Intent.ACTION_VIEW);
            i.putExtra("ario_go", "chat:" + link);
            IconCompat ic = IconCompat.createWithBitmap(icon);
            Person who = new Person.Builder().setName(title).setKey("c_" + link).setIcon(ic).build();
            ShortcutInfoCompat sc = new ShortcutInfoCompat.Builder(c, "chat_" + link)
                    .setShortLabel(title.isEmpty() ? "Ario" : title)
                    .setLongLived(true)
                    .setIntent(i)
                    .setPerson(who)
                    .setIcon(ic)
                    .setCategories(Collections.singleton("androidx.core.content.pm.SHORTCUT_CATEGORY_CONVERSATION"))
                    .build();
            ShortcutManagerCompat.pushDynamicShortcut(c, sc);
        } catch (Throwable t) {
            // the notification still works without it
        }
    }

    /** mine = a reply sent from the notification itself: shown as "me", silent, no new sound. */
    static void post(Context c, String link, String groupTitle, String from, String text, long when, String p, int hue, String letter, boolean mine, String kind) {
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
            h.put("n", from).put("me", mine ? 1 : 0).put("t", text).put("w", when > 0 ? when : System.currentTimeMillis()).put("p", p).put("h", hue).put("l", letter);
            hist.put(h);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, hist.length() - 6); i < hist.length(); i++) keep.put(hist.get(i));
            sp.edit().putString("h_" + link, keep.toString())
                    .putString("i_" + link, new JSONObject().put("g", groupTitle).put("t", groupTitle.isEmpty() ? from : groupTitle).put("k", kind == null ? "" : kind).toString()).apply();

            Person me = new Person.Builder().setName(ArioLang.t(c, "من", "Me")).build();
            NotificationCompat.MessagingStyle st = new NotificationCompat.MessagingStyle(me);
            boolean group = !groupTitle.isEmpty();
            if (group) {
                st.setConversationTitle(groupTitle);
                st.setGroupConversation(true);
            }
            boolean advisor = "a".equals(kind);
            Bitmap appBm = advisor ? appIcon(c) : null;   // advisor chat = the app icon
            Bitmap last = null;
            for (int i = 0; i < keep.length(); i++) {
                JSONObject o = keep.getJSONObject(i);
                if (o.optInt("me", 0) == 1) {
                    st.addMessage(o.optString("t", ""), o.optLong("w", System.currentTimeMillis()), (Person) null);
                    continue;
                }
                Bitmap bm = appBm != null ? appBm : avatar(c, o.optString("p", ""), o.optInt("h", 0), o.optString("l", ""));
                last = bm;
                Person sender = new Person.Builder().setName(o.optString("n", "")).setKey(o.optString("n", "")).setIcon(IconCompat.createWithBitmap(bm)).build();
                st.addMessage(o.optString("t", ""), o.optLong("w", System.currentTimeMillis()), sender);
            }
            // the picture of the chat: advisor = app icon, group = the group's round letter picture, friend = the friend's profile picture
            String chatName = group ? groupTitle : from;
            int chatHue = hueOf(chatName);
            Bitmap chatBm;
            if (advisor && appBm != null) chatBm = appBm;
            else if (group) chatBm = avatar(c, "", chatHue, firstLetter(groupTitle));
            else chatBm = last != null ? last : avatar(c, p, hue, letter.isEmpty() ? firstLetter(from) : letter);
            pushShortcut(c, link, chatName, chatBm);
            int accent = advisor ? Color.parseColor("#3D63F0") : Color.HSVToColor(new float[]{chatHue, 0.55f, 0.62f});   // no fixed orange any more
            int small = ArioNotifyCore.iconRes(c, "ic_stat_adv");
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(small != 0 ? small : android.R.drawable.ic_dialog_email)
                    .setColor(accent)
                    .setShortcutId("chat_" + link)
                    .setContentTitle(group ? groupTitle : from)
                    .setContentText(mine ? ArioLang.t(c, "شما: ", "You: ") + text : (group ? from + ": " + text : text))
                    .setStyle(st)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(mine ? 0 : Notification.DEFAULT_ALL)
                    .setOnlyAlertOnce(mine)
                    .setAutoCancel(true)
                    .setWhen(when > 0 ? when : System.currentTimeMillis())
                    .setShowWhen(true)
                    .setContentIntent(ArioNotifyCore.openPi(c, id, "chat:" + link));
            b.setLargeIcon(chatBm);
            b.addAction(replyAction(c, id, link));
            b.addAction(readAction(c, id, link));
            nm.notify(id, b.build());
        } catch (Throwable t) {
            // never crash the worker
        }
    }

    /* ---------- buttons on the notification: reply + mark as read ---------- */
    static final String KEY_REPLY = "ario_reply";

    static PendingIntent actionPi(Context c, String act, int nid, String link, boolean mutable) {
        Intent i = new Intent(c, ArioNotifyReceiver.class);
        i.setAction(act);
        i.setData(Uri.parse("ario://chat/" + act + "/" + link));
        i.putExtra("nid", nid);
        i.putExtra("link", link);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fl |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !mutable) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, nid + (mutable ? 1 : 2), i, fl);
    }

    static NotificationCompat.Action replyAction(Context c, int nid, String link) {
        RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel(ArioLang.t(c, "پیام…", "Message…")).build();
        return new NotificationCompat.Action.Builder(0, ArioLang.t(c, "پاسخ", "Reply"), actionPi(c, "ario.chat.reply", nid, link, true))
                .addRemoteInput(ri)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(true)
                .build();
    }

    static NotificationCompat.Action readAction(Context c, int nid, String link) {
        return new NotificationCompat.Action.Builder(0, ArioLang.t(c, "خوانده شد", "Mark as read"), actionPi(c, "ario.chat.read", nid, link, false))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build();
    }

    static void toast(final Context c, final String m) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    Toast.makeText(c, m, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    // ignore
                }
            }
        });
    }

    /** A valid access token for the background calls, or "" when there is no usable sign-in. */
    static String token(Context c) {
        SharedPreferences s = ArioCounselorWorker.sp(c);
        if (!s.getBoolean("on", false)) return "";
        if (s.getLong("exp", 0) < System.currentTimeMillis() / 1000 + 120) {
            if (!ArioCounselorWorker.refresh(c)) return "";
        }
        return s.getString("at", "");
    }

    /** POST json to Supabase; renews the sign-in once on 401. Returns the HTTP status (0 = no connection). */
    static int postJson(Context c, String path, JSONObject body, boolean wantBody) {
        SharedPreferences s = ArioCounselorWorker.sp(c);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String tok = token(c);
                if (tok.isEmpty()) return 401;
                HttpURLConnection h = (HttpURLConnection) new URL(s.getString("url", "") + path).openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(15000);
                h.setReadTimeout(20000);
                h.setRequestProperty("apikey", s.getString("key", ""));
                h.setRequestProperty("Authorization", "Bearer " + tok);
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("Prefer", "return=minimal");
                h.setDoOutput(true);
                OutputStream os = h.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();
                int code = h.getResponseCode();
                if (code == 401 && attempt == 0) {
                    if (!ArioCounselorWorker.refresh(c)) return 401;
                    continue;
                }
                return code;
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }

    static void markSeen(Context c, String link) {
        try {
            postJson(c, "/rest/v1/rpc/mark_chat_seen", new JSONObject().put("p_link", link), false);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Runs on a background thread from ArioNotifyReceiver: \"mark as read\" and \"reply\" taken straight from the notification. */
    static void handleAction(Context c, Intent in) {
        try {
            String act = in.getAction(), link = in.getStringExtra("link");
            if (link == null || link.isEmpty()) return;
            int nid = 2100001000 + (Math.abs(link.hashCode()) % 90000);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if ("ario.chat.read".equals(act)) {
                nm.cancel(nid);
                sp.edit().remove("h_" + link).apply();
                markSeen(c, link);
                return;
            }
            Bundle r = RemoteInput.getResultsFromIntent(in);
            CharSequence cs = r == null ? null : r.getCharSequence(KEY_REPLY);
            String text = cs == null ? "" : cs.toString().trim();
            if (text.isEmpty()) return;
            if (text.length() > 3000) text = text.substring(0, 3000);

            JSONObject inf;
            try {
                inf = new JSONObject(sp.getString("i_" + link, "{}"));
            } catch (Throwable t) {
                inf = new JSONObject();
            }
            String groupTitle = inf.optString("g", ""), title = inf.optString("t", ""), kind = inf.optString("k", "");
            boolean group = !groupTitle.isEmpty();

            JSONObject me;
            try {
                me = new JSONObject(ArioCounselorWorker.sp(c).getString("me", "{}"));
            } catch (Throwable t) {
                me = new JSONObject();
            }
            JSONObject meta = new JSONObject();
            JSONObject av = me.optJSONObject("av");
            if (av != null) meta.put("av", av);
            if (group && !me.optString("nm", "").isEmpty()) {
                String nm0 = me.optString("nm", "");
                meta.put("nm", nm0.length() > 80 ? nm0.substring(0, 80) : nm0);
            }
            JSONObject row = new JSONObject().put("id", UUID.randomUUID().toString()).put("link_id", link)
                    .put("sender_id", ArioCounselorWorker.sp(c).getString("uid", "")).put("kind", "t").put("body", text).put("meta", meta);
            int code = postJson(c, "/rest/v1/link_messages", row, false);
            if (code >= 200 && code < 300) {
                markSeen(c, link);
                post(c, link, groupTitle, title, text, System.currentTimeMillis(), "", 0, "", true, kind);   // the notification now shows your reply
            } else {
                post(c, link, groupTitle, title, ArioLang.t(c, "⚠️ ارسال نشد: ", "⚠️ Not sent: ") + text, System.currentTimeMillis(), "", 0, "", true, kind);
                toast(c, ArioLang.t(c, "پیام ارسال نشد؛ اینترنت را بررسی کن", "Message not sent - check your connection"));
            }
        } catch (Throwable t) {
            // never crash the receiver
        }
    }
}
