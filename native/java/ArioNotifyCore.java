package __APPID__;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/** Shared logic for Ario's own themed notifications: alarms, stored items, drawing the card (light / dark / profile banner). */
public class ArioNotifyCore {
    static final String PREFS = "ario_notif";
    static final String K_ITEMS = "items";
    static final String K_ACTS = "acts";
    static final String K_THEME = "theme";
    static final String BANNER_FILE = "notif_banner.jpg";
    static final String AVATAR_FILE = "notif_avatar.png";
    static final String K_PINON = "pinon";
    static final String K_PIN = "pin";
    static final String K_GO = "go";
    static final String K_OPTS = "opts";
    static final int PIN_ID = 2100000600;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized JSONArray load(Context c, String key) {
        try {
            return new JSONArray(sp(c).getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void save(Context c, String key, JSONArray a) {
        sp(c).edit().putString(key, a.toString()).apply();
    }

    /* ---------- style ---------- */
    static void saveStyle(Context c, String theme, String bannerB64, String avatarB64, String optsJson) {
        sp(c).edit().putString(K_THEME, theme == null ? "sys" : theme).putString(K_OPTS, optsJson == null ? "{}" : optsJson).apply();
        File f = new File(c.getFilesDir(), BANNER_FILE);
        try {
            if (bannerB64 != null && !bannerB64.isEmpty()) {
                byte[] bytes = android.util.Base64.decode(bannerB64, android.util.Base64.DEFAULT);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(bytes);
                fo.close();
            } else if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            // keep going with a plain background
        }
        File af = new File(c.getFilesDir(), AVATAR_FILE);
        try {
            if (avatarB64 != null && !avatarB64.isEmpty()) {
                byte[] bytes = android.util.Base64.decode(avatarB64, android.util.Base64.DEFAULT);
                FileOutputStream fo = new FileOutputStream(af);
                fo.write(bytes);
                fo.close();
            } else if (af.exists()) {
                af.delete();
            }
        } catch (Exception e) {
            // keep the app icon
        }
    }

    /** Appearance options chosen in the app: bg / ac (hex colours), tx (s|m|l), bar, li (photo|app|none), ab (action buttons). */
    static JSONObject opts(Context c) {
        try {
            return new JSONObject(sp(c).getString(K_OPTS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static int parseHex(String h, int def) {
        try {
            if (h != null && h.length() > 0) return Color.parseColor(h);
        } catch (Exception e) {
            // use the default
        }
        return def;
    }

    static float textScale(Context c) {
        String t = opts(c).optString("tx", "m");
        if ("s".equals(t)) return 0.9f;
        if ("l".equals(t)) return 1.14f;
        return 1f;
    }

    /** The accent colour the person picked for every notification, or the notification's own colour when they left it on "auto". */
    static int accent(Context c, int own) {
        return parseHex(opts(c).optString("ac", ""), own);
    }

    static boolean isLight(int col) {
        double l = (0.299 * Color.red(col) + 0.587 * Color.green(col) + 0.114 * Color.blue(col)) / 255.0;
        return l > 0.6;
    }

    /** Large icon of every notification: the user's profile photo, or the app icon when none is chosen (or no icon at all). */
    static Bitmap largeIcon(Context c) {
        String li = opts(c).optString("li", "photo");
        if ("none".equals(li)) return null;
        try {
            File af = new File(c.getFilesDir(), AVATAR_FILE);
            if (!"app".equals(li) && af.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(af.getAbsolutePath());
                if (bm != null) return bm;
            }
        } catch (Throwable t) {
            // fall back to the app icon
        }
        int large = c.getResources().getIdentifier("ic_notif_large", "drawable", c.getPackageName());
        return large != 0 ? BitmapFactory.decodeResource(c.getResources(), large) : null;
    }

    static String theme(Context c) {
        return sp(c).getString(K_THEME, "sys");
    }

    /** Colours the card (background, accent bar, text) for the chosen theme. Returns false when the system look should be used. */
    static boolean paint(Context c, RemoteViews v, int barColor) {
        return paint(c, v, barColor, 15f, 12.5f);
    }

    static boolean paint(Context c, RemoteViews v, int barColor, float titleSp, float textSp) {
        String th = theme(c);
        if ("sys".equals(th)) {
            v.setViewVisibility(R.id.nc_bg, View.GONE);
            v.setViewVisibility(R.id.nc_bar, View.GONE);
            return false;
        }
        JSONObject o = opts(c);
        int bg = 0xFFFFFFFF, fg = 0xFF14203F, fg2 = 0xFF5B6680;
        if ("dark".equals(th)) {
            bg = 0xFF1B2033;
            fg = 0xFFFFFFFF;
            fg2 = 0xCCFFFFFF;
        } else if ("banner".equals(th)) {
            bg = 0xFF1B2033;
            fg = 0xFFFFFFFF;
            fg2 = 0xE6FFFFFF;
        } else if ("custom".equals(th)) {
            bg = 0xFF000000 | (parseHex(o.optString("bg", ""), 0xFF1B2033) & 0xFFFFFF);
            if (isLight(bg)) {
                fg = 0xFF14203F;
                fg2 = 0xFF4A5470;
            } else {
                fg = 0xFFFFFFFF;
                fg2 = 0xDDFFFFFF;
            }
        }
        v.setViewVisibility(R.id.nc_bg, View.VISIBLE);
        v.setViewVisibility(R.id.nc_bar, o.optBoolean("bar", true) ? View.VISIBLE : View.GONE);
        v.setInt(R.id.nc_bg, "setBackgroundColor", bg);
        if ("banner".equals(th)) {
            try {
                File f = new File(c.getFilesDir(), BANNER_FILE);
                if (f.exists()) {
                    BitmapFactory.Options bo = new BitmapFactory.Options();
                    bo.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), bo);
                    if (bm != null) v.setImageViewBitmap(R.id.nc_bg, bm);
                }
            } catch (Throwable t) {
                // plain dark background stays
            }
        }
        v.setInt(R.id.nc_bar, "setBackgroundColor", barColor);
        v.setTextColor(R.id.nc_title, fg);
        v.setTextColor(R.id.nc_text, fg2);
        float k = textScale(c);
        v.setTextViewTextSize(R.id.nc_title, android.util.TypedValue.COMPLEX_UNIT_SP, titleSp * k);
        v.setTextViewTextSize(R.id.nc_text, android.util.TypedValue.COMPLEX_UNIT_SP, textSp * k);
        return true;
    }

    static int fgColor(Context c) {
        String th = theme(c);
        if ("custom".equals(th)) return isLight(0xFF000000 | (parseHex(opts(c).optString("bg", ""), 0xFF1B2033) & 0xFFFFFF)) ? 0xFF14203F : 0xFFFFFFFF;
        return ("dark".equals(th) || "banner".equals(th)) ? 0xFFFFFFFF : 0xFF14203F;
    }

    /** Colour of an item: the one the web app sent (per section), else the colour of its kind. */
    static int colorOf(JSONObject it, String kind) {
        String col = it.optString("col", "");
        if (col.length() > 0) {
            try {
                return Color.parseColor(col);
            } catch (Exception e) {
                // fall back to the kind colour
            }
        }
        return kindColor(kind);
    }

    static int kindColor(String kind) {
        if ("sum".equals(kind)) return Color.parseColor("#F59E0B");
        if ("eve".equals(kind)) return Color.parseColor("#7C4DFF");
        if ("wr".equals(kind)) return Color.parseColor("#16A34A");
        if ("fo".equals(kind)) return Color.parseColor("#FF375F");
        return Color.parseColor("#3D63F0");
    }

    /* ---------- alarms ---------- */
    static PendingIntent firePi(Context c, int id) {
        Intent i = new Intent(c, ArioNotifyReceiver.class);
        i.setAction("ario.fire");
        i.setData(Uri.parse("ario://fire/" + id));
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setAlarm(Context c, JSONObject it) {
        long at = it.optLong("at", 0L);
        int id = it.optInt("id", 0);
        if (at <= 0 || id == 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = firePi(c, id);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAlarm(Context c, int id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(firePi(c, id));
    }

    static synchronized void replaceAll(Context c, JSONArray items, Set<Integer> keep) {
        JSONArray old = load(c, K_ITEMS), out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (keep.contains(o.optInt("id"))) out.put(o);
            else cancelAlarm(c, o.optInt("id"));
        }
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            out.put(o);
            setAlarm(c, o);
        }
        save(c, K_ITEMS, out);
    }

    static synchronized void add(Context c, JSONArray items) {
        JSONArray old = load(c, K_ITEMS), out = new JSONArray();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) ids.add(o.optInt("id"));
        }
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !ids.contains(o.optInt("id"))) out.put(o);
        }
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            out.put(o);
            setAlarm(c, o);
        }
        save(c, K_ITEMS, out);
    }

    static synchronized void cancel(Context c, JSONArray ids) {
        Set<Integer> rm = new HashSet<>();
        for (int i = 0; ids != null && i < ids.length(); i++) rm.add(ids.optInt(i));
        JSONArray old = load(c, K_ITEMS), out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (rm.contains(o.optInt("id"))) cancelAlarm(c, o.optInt("id"));
            else out.put(o);
        }
        save(c, K_ITEMS, out);
    }

    static synchronized JSONObject pop(Context c, int id) {
        JSONArray old = load(c, K_ITEMS), out = new JSONArray();
        JSONObject found = null;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (o.optInt("id") == id && found == null) found = o;
            else out.put(o);
        }
        save(c, K_ITEMS, out);
        return found;
    }

    static synchronized void rescheduleAll(Context c) {
        JSONArray old = load(c, K_ITEMS), out = new JSONArray();
        long now = System.currentTimeMillis();
        JSONObject lastPin = null;
        long lastPinAt = 0L;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            long at = o.optLong("at", 0L);
            if (at <= now) {
                // the newest pinned-bar snapshot that was due while the phone was off is shown again
                if ("pin".equals(o.optString("kind")) && at > lastPinAt) {
                    lastPin = o;
                    lastPinAt = at;
                }
                continue;
            }
            out.put(o);
            setAlarm(c, o);
        }
        save(c, K_ITEMS, out);
        try {
            if (lastPin != null) {
                pinPost(c, lastPin);
            } else {
                String saved = sp(c).getString(K_PIN, "");
                if (!saved.isEmpty()) pinPost(c, new JSONObject(saved));
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    static synchronized void addAction(Context c, String a, String tid, long at) {
        try {
            JSONArray arr = load(c, K_ACTS);
            JSONObject o = new JSONObject();
            o.put("a", a);
            o.put("tid", tid == null ? "" : tid);
            o.put("at", at);
            arr.put(o);
            save(c, K_ACTS, arr);
        } catch (Exception e) {
            // ignore
        }
    }

    static synchronized JSONArray takeActions(Context c) {
        JSONArray a = load(c, K_ACTS);
        save(c, K_ACTS, new JSONArray());
        return a;
    }

    /* ---------- posting ---------- */
    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel("ario_rem") == null) {
            NotificationChannel ch = new NotificationChannel("ario_rem", ArioLang.t(c, "یادآورها", "Reminders"), NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.enableLights(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("ario_sum") == null) {
            NotificationChannel ch = new NotificationChannel("ario_sum", ArioLang.t(c, "خلاصه روزانه", "Daily summary"), NotificationManager.IMPORTANCE_DEFAULT);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("ario_asst") == null) {
            NotificationChannel ch = new NotificationChannel("ario_asst", ArioLang.t(c, "پیام‌های دستیار Ario", "Ario assistant messages"), NotificationManager.IMPORTANCE_DEFAULT);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("ario_adv") == null) {
            NotificationChannel ch = new NotificationChannel("ario_adv", ArioLang.t(c, "پیام‌های مشاور", "Advisor messages"), NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.enableLights(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("ario_part") == null) {
            NotificationChannel ch = new NotificationChannel("ario_part", ArioLang.t(c, "پایان پارت", "Part finished"), NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.enableLights(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("ario_pin") == null) {
            NotificationChannel ch = new NotificationChannel("ario_pin", ArioLang.t(c, "نوار اعلان ثابت", "Pinned notification"), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(ArioLang.t(c, "کار بعدی و پیشرفت امروز، همیشه در نوار اعلان", "Next task and today's progress, always in the shade"));
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("ario_fo") == null) {
            NotificationChannel ch = new NotificationChannel("ario_fo", ArioLang.t(c, "پایان تمرکز", "Focus finished"), NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    static PendingIntent actionPi(Context c, String act, int id, String tid, String itemJson) {
        Intent i = new Intent(c, ArioNotifyReceiver.class);
        i.setAction(act);
        i.setData(Uri.parse("ario://" + act + "/" + id));
        i.putExtra("id", id);
        i.putExtra("tid", tid);
        i.putExtra("item", itemJson);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static RemoteViews card(Context c, boolean big, String title, String text, int barColor) {
        RemoteViews v = new RemoteViews(c.getPackageName(), big ? R.layout.notif_card_big : R.layout.notif_card);
        v.setTextViewText(R.id.nc_title, title);
        v.setTextViewText(R.id.nc_text, text);
        if (big) paint(c, v, barColor, 16f, 13f);
        else paint(c, v, barColor, 15f, 12.5f);
        return v;
    }

    /** Tapping a notification opens the app; the section to show is passed along and read by the web app. */
    static PendingIntent openPi(Context c, int id, String go) {
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open == null) open = new Intent();
        if (go != null && !go.isEmpty()) open.putExtra("ario_go", go);
        return PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int iconRes(Context c, String name) {
        int r = 0;
        if (name != null && !name.isEmpty()) r = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (r == 0) r = c.getResources().getIdentifier("ic_stat_barnameyar", "drawable", c.getPackageName());
        return r;
    }

    static void post(Context c, JSONObject it) {
        try {
            if ("pin".equals(it.optString("kind"))) {
                pinPost(c, it);
                return;
            }
            ensureChannels(c);
            int id = it.optInt("id");
            String kind = it.optString("kind", "rem");
            String ch = it.optString("ch", "rem");
            String title = it.optString("title", "Ario");
            String body = it.optString("body", "");
            String bigText = it.optString("big", body);
            String tid = it.optString("tid", "");
            String sub = it.optString("sub", "");
            int color = accent(c, colorOf(it, kind));
            int small = iconRes(c, it.optString("ico", ""));
            PendingIntent pi = openPi(c, id, it.optString("go", ""));

            boolean chat = "asst".equals(ch) || "adv".equals(ch);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, "ario_" + ch)
                    .setSmallIcon(small != 0 ? small : android.R.drawable.ic_popup_reminder)
                    .setColor(color)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(chat ? NotificationCompat.CATEGORY_MESSAGE : ("rem".equals(ch) || "part".equals(ch)) ? NotificationCompat.CATEGORY_REMINDER : NotificationCompat.CATEGORY_EVENT)
                    .setPriority(("sum".equals(ch) || "asst".equals(ch)) ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_HIGH);
            if (!sub.isEmpty()) b.setSubText(sub);
            if ("sys".equals(theme(c))) {
                b.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
            } else {
                b.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomContentView(card(c, false, title, body, color))
                        .setCustomBigContentView(card(c, true, title, bigText, color));
            }
            Bitmap lbm = largeIcon(c);
            if (lbm != null) b.setLargeIcon(lbm);
            if (it.optBoolean("act", false) && opts(c).optBoolean("ab", true)) {
                String json = it.toString();
                b.addAction(0, ArioLang.t(c, "۱۰ دقیقه بعد", "Snooze 10 min"), actionPi(c, "ario.snooze", id, tid, json));
                b.addAction(0, ArioLang.t(c, "انجام شد", "Done"), actionPi(c, "ario.done", id, tid, json));
            }
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(id, b.build());
        } catch (Throwable t) {
            // never crash the receiver
        }
    }

    /* ---------- pinned (always-there) notification ---------- */
    /** Shows the pinned bar with the given snapshot; does nothing when the user switched the bar off. */
    static void pinPost(Context c, JSONObject it) {
        try {
            if (!sp(c).getBoolean(K_PINON, false)) return;
            ensureChannels(c);
            sp(c).edit().putString(K_PIN, it.toString()).apply();
            String title = it.optString("title", "Ario");
            String text = it.optString("text", "");
            String sub = it.optString("sub", "");
            String left = it.optString("left", "");
            long end = it.optLong("end", 0L);
            long remain = end - System.currentTimeMillis();
            boolean run = end > 0 && remain > 0;
            int color = accent(c, colorOf(it, "rem"));

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.notif_focus);
            v.setTextViewText(R.id.nc_title, title);
            v.setTextViewText(R.id.nc_text, text);
            boolean themed = paint(c, v, color);
            if (themed) {
                v.setTextColor(R.id.nf_time, fgColor(c));
                v.setTextColor(R.id.nf_static, fgColor(c));
            }
            if (run) {
                v.setViewVisibility(R.id.nf_static, View.GONE);
                v.setViewVisibility(R.id.nf_time, View.VISIBLE);
                v.setChronometer(R.id.nf_time, android.os.SystemClock.elapsedRealtime() + remain, null, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) v.setChronometerCountDown(R.id.nf_time, true);
            } else if (!left.isEmpty()) {
                v.setViewVisibility(R.id.nf_time, View.GONE);
                v.setViewVisibility(R.id.nf_static, View.VISIBLE);
                v.setTextViewText(R.id.nf_static, left);
            } else {
                v.setViewVisibility(R.id.nf_time, View.GONE);
                v.setViewVisibility(R.id.nf_static, View.GONE);
            }

            NotificationCompat.Builder b = new NotificationCompat.Builder(c, "ario_pin")
                    .setSmallIcon(iconRes(c, it.optString("ico", "")))
                    .setColor(color)
                    .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(v)
                    .setContentIntent(openPi(c, PIN_ID, it.optString("go", "")))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(it.optBoolean("lock", true) ? NotificationCompat.VISIBILITY_PUBLIC : NotificationCompat.VISIBILITY_PRIVATE)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            if (!sub.isEmpty()) b.setSubText(sub);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(PIN_ID, b.build());
        } catch (Throwable t) {
            // never crash the receiver
        }
    }

    static void pinClear(Context c) {
        try {
            sp(c).edit().remove(K_PIN).apply();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(PIN_ID);
        } catch (Throwable t) {
            // ignore
        }
    }

    /* ---------- "open this section" after tapping a notification ---------- */
    static void stashGo(Context c, Intent i) {
        try {
            if (i == null) return;
            String g = i.getStringExtra("ario_go");
            if (g != null && !g.isEmpty()) {
                sp(c).edit().putString(K_GO, g).apply();
                i.removeExtra("ario_go");
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    static String takeGo(Context c) {
        String g = sp(c).getString(K_GO, "");
        if (!g.isEmpty()) sp(c).edit().remove(K_GO).apply();
        return g;
    }
}
