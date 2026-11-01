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
    static void saveStyle(Context c, String theme, String bannerB64, String avatarB64) {
        sp(c).edit().putString(K_THEME, theme == null ? "sys" : theme).apply();
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

    /** Large icon of every notification: the user's profile photo, or the app icon when none is chosen. */
    static Bitmap largeIcon(Context c) {
        try {
            File af = new File(c.getFilesDir(), AVATAR_FILE);
            if (af.exists()) {
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
        String th = theme(c);
        if ("sys".equals(th)) {
            v.setViewVisibility(R.id.nc_bg, View.GONE);
            v.setViewVisibility(R.id.nc_bar, View.GONE);
            return false;
        }
        int bg = 0xFFFFFFFF, fg = 0xFF14203F, fg2 = 0xFF5B6680;
        if ("dark".equals(th)) {
            bg = 0xFF1B2033;
            fg = 0xFFFFFFFF;
            fg2 = 0xCCFFFFFF;
        } else if ("banner".equals(th)) {
            bg = 0xFF1B2033;
            fg = 0xFFFFFFFF;
            fg2 = 0xE6FFFFFF;
        }
        v.setViewVisibility(R.id.nc_bg, View.VISIBLE);
        v.setViewVisibility(R.id.nc_bar, View.VISIBLE);
        v.setInt(R.id.nc_bg, "setBackgroundColor", bg);
        if ("banner".equals(th)) {
            try {
                File f = new File(c.getFilesDir(), BANNER_FILE);
                if (f.exists()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                    if (bm != null) v.setImageViewBitmap(R.id.nc_bg, bm);
                }
            } catch (Throwable t) {
                // plain dark background stays
            }
        }
        v.setInt(R.id.nc_bar, "setBackgroundColor", barColor);
        v.setTextColor(R.id.nc_title, fg);
        v.setTextColor(R.id.nc_text, fg2);
        return true;
    }

    static int fgColor(Context c) {
        String th = theme(c);
        return ("dark".equals(th) || "banner".equals(th)) ? 0xFFFFFFFF : 0xFF14203F;
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
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null || o.optLong("at", 0L) <= now) continue;
            out.put(o);
            setAlarm(c, o);
        }
        save(c, K_ITEMS, out);
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
        paint(c, v, barColor);
        return v;
    }

    static void post(Context c, JSONObject it) {
        try {
            ensureChannels(c);
            int id = it.optInt("id");
            String kind = it.optString("kind", "rem");
            String ch = it.optString("ch", "rem");
            String title = it.optString("title", "Ario");
            String body = it.optString("body", "");
            String bigText = it.optString("big", body);
            String tid = it.optString("tid", "");
            int color = kindColor(kind);
            int small = c.getResources().getIdentifier("ic_stat_barnameyar", "drawable", c.getPackageName());
            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(c, "ario_" + ch)
                    .setSmallIcon(small != 0 ? small : android.R.drawable.ic_popup_reminder)
                    .setColor(color)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory("rem".equals(ch) ? NotificationCompat.CATEGORY_REMINDER : NotificationCompat.CATEGORY_EVENT)
                    .setPriority("sum".equals(ch) ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_HIGH);
            if ("sys".equals(theme(c))) {
                b.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
            } else {
                b.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomContentView(card(c, false, title, body, color))
                        .setCustomBigContentView(card(c, true, title, bigText, color));
            }
            Bitmap lbm = largeIcon(c);
            if (lbm != null) b.setLargeIcon(lbm);
            if (it.optBoolean("act", false)) {
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
}
