package __APPID__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Focus guard. While a focus window is active (the focus timer is running, or a timetable "part" is in progress)
 * it watches which app is in front (needs the "Usage access" permission) and, when it is one of the apps the
 * user picked, either shows a warning or covers it with a full-screen "back to study" screen
 * (needs "Display over other apps").
 */
public class FocusGuardService extends Service {
    static final String PREFS = "ario_focus_guard";
    private static final String CH = "focus_guard";
    private static final String CH_WARN = "focus_guard_warn";
    private static final int NID = 2100000801;
    private static final int NID_WARN = 2100000802;
    private static final long FAST = 900L;      // polling while a focus window is active
    private static final long IDLE = 15000L;    // polling while waiting for the next window

    private final Handler h = new Handler(Looper.getMainLooper());
    private String cur = null;          // package that is in front now
    private long lastQ = 0L;            // last time we read usage events
    private String lastHit = null;      // blocked package we already reacted to
    private long lastWarnAt = 0L;
    private int lastNotifState = -1;    // 0 idle, 1 active
    private long lastNotifEnd = -1L;
    private WindowManager wm;
    private View overlay;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            long next = IDLE;
            try {
                next = tick();
            } catch (Throwable t) {
                // keep running
            }
            h.postDelayed(this, next);
        }
    };

    /** Starts the service when the saved settings say it should run (also used after a reboot). */
    static void ensure(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!p.getBoolean("on", false)) return;
            Intent i = new Intent(c, FocusGuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            // not allowed right now (background start); the app will start it next time it opens
        }
    }

    static boolean hasUsageAccess(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                mode = ao.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName());
            else
                mode = ao.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat(buildNotif(false, 0L));
            lastNotifState = 0;
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        h.removeCallbacks(loop);
        h.post(loop);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        hideOverlay();
        super.onDestroy();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, 1073741824); // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else startForeground(NID, n);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** End of the focus window we are in right now, or 0 when none is active. */
    private long activeUntil(SharedPreferences p, long now) {
        long end = 0L;
        long te = p.getLong("te", 0L);
        if (te > now) end = te;
        try {
            JSONArray w = new JSONArray(p.getString("wins", "[]"));
            for (int i = 0; i < w.length(); i++) {
                JSONArray a = w.getJSONArray(i);
                long s = a.getLong(0), e = a.getLong(1);
                if (now >= s && now < e && e > end) end = e;
            }
        } catch (Throwable t) {
            // ignore a broken list
        }
        return end;
    }

    private long tick() {
        SharedPreferences p = prefs();
        if (!p.getBoolean("on", false)) {
            stopSelf();
            return IDLE;
        }
        long now = System.currentTimeMillis();
        long end = activeUntil(p, now);
        if (end == 0L) {
            hideOverlay();
            cur = null;
            lastQ = 0L;
            lastHit = null;
            setNotif(false, 0L);
            return IDLE;
        }
        setNotif(true, end);
        if (!hasUsageAccess(this)) return IDLE;

        readForeground(now);
        Set<String> blocked = p.getStringSet("pk", Collections.<String>emptySet());
        boolean hit = cur != null && blocked != null && blocked.contains(cur) && now >= p.getLong("snooze", 0L);
        if (!hit) {
            hideOverlay();
            lastHit = null;
            return FAST;
        }
        boolean block = "block".equals(p.getString("mode", "warn")) && canOverlay(this);
        if (block) {
            if (overlay == null || !cur.equals(lastHit)) showOverlay(cur, end, p);
        } else if (!cur.equals(lastHit) || now - lastWarnAt > 60000L) {
            warn(cur, end, p);
            lastWarnAt = now;
        }
        lastHit = cur;
        return FAST;
    }

    /** Keeps "which app is in front" up to date from the usage events. */
    private void readForeground(long now) {
        UsageStatsManager um = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (um == null) return;
        long from = lastQ > 0 ? lastQ - 1000L : now - 3600000L;
        UsageEvents ev = um.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev != null && ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            String pk = e.getPackageName();
            if (t == 1) cur = pk;                                   // moved to foreground
            else if (t == 2 && pk != null && pk.equals(cur)) cur = null; // moved to background
        }
        lastQ = now;
    }

    private String label(String pk) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pk, 0)));
        } catch (Throwable t) {
            return pk;
        }
    }

    private String hm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- warning ----------
    private void warn(String pk, long end, SharedPreferences p) {
        String msg = p.getString("t_warn", ArioLang.t(this, "الان وقت تمرکز است؛ برگرد سر درس 📚", "It's focus time. Back to study 📚"));
        String line = label(pk) + " · " + ArioLang.t(this, "تا ", "until ") + hm(end);
        try {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // ignore
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable t) {
            // ignore
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CH_WARN) == null) {
            NotificationChannel ch = new NotificationChannel(CH_WARN, ArioLang.t(this, "هشدار قفل تمرکز", "Focus guard warnings"), NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CH_WARN)
                .setSmallIcon(smallIcon())
                .setContentTitle(msg)
                .setContentText(line)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(15000L)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();
        nm.notify(NID_WARN, n);
    }

    // ---------- full-screen block ----------
    private void showOverlay(final String pk, long end, final SharedPreferences p) {
        hideOverlay();
        try {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(Color.parseColor("#F2101830"));
            root.setPadding(dp(28), dp(28), dp(28), dp(28));
            root.setLayoutDirection(ArioLang.en(this) ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
            root.setClickable(true); // swallow touches so the app below can't be used

            TextView ico = new TextView(this);
            ico.setText("🔒");
            ico.setTextSize(54);
            ico.setGravity(Gravity.CENTER);
            root.addView(ico);

            TextView t1 = new TextView(this);
            t1.setText(p.getString("t_title", ArioLang.t(this, "الان وقت مطالعه است", "It's study time")));
            t1.setTextColor(Color.WHITE);
            t1.setTextSize(22);
            t1.setGravity(Gravity.CENTER);
            t1.setPadding(0, dp(12), 0, dp(6));
            root.addView(t1);

            TextView t2 = new TextView(this);
            t2.setText(label(pk) + " · " + ArioLang.t(this, "تا ساعت ", "until ") + hm(end) + "\n"
                    + p.getString("t_msg", ArioLang.t(this, "این برنامه تا پایان تمرکز بسته است.", "This app is locked until focus time ends.")));
            t2.setTextColor(Color.parseColor("#C8D0E8"));
            t2.setTextSize(15);
            t2.setGravity(Gravity.CENTER);
            t2.setLineSpacing(0f, 1.25f);
            root.addView(t2);

            Button back = new Button(this);
            back.setText(p.getString("t_back", ArioLang.t(this, "برگرد به Ario", "Back to Ario")));
            back.setTextColor(Color.WHITE);
            back.setAllCaps(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#3D63F0"));
            bg.setCornerRadius(dp(26));
            back.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.topMargin = dp(28);
            back.setLayoutParams(lp);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (open != null) {
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(open);
                    }
                    hideOverlay();
                }
            });
            root.addView(back);

            if (p.getBoolean("sn", true)) {
                Button rest = new Button(this);
                rest.setText(p.getString("t_rest", ArioLang.t(this, "۵ دقیقه استراحت", "5-minute break")));
                rest.setTextColor(Color.parseColor("#C8D0E8"));
                rest.setAllCaps(false);
                GradientDrawable bg2 = new GradientDrawable();
                bg2.setColor(Color.parseColor("#22FFFFFF"));
                bg2.setCornerRadius(dp(26));
                rest.setBackground(bg2);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
                lp2.topMargin = dp(12);
                rest.setLayoutParams(lp2);
                rest.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        p.edit().putLong("snooze", System.currentTimeMillis() + 300000L).apply();
                        hideOverlay();
                    }
                });
                root.addView(rest);
            }

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lpw = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            wm.addView(root, lpw);
            overlay = root;
        } catch (Throwable t) {
            overlay = null;
        }
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try {
            if (wm != null) wm.removeView(overlay);
        } catch (Throwable t) {
            // already gone
        }
        overlay = null;
    }

    // ---------- the permanent notification ----------
    private int smallIcon() {
        int s = getResources().getIdentifier("ic_stat_barnameyar", "drawable", getPackageName());
        return s != 0 ? s : android.R.drawable.ic_lock_idle_lock;
    }

    private Notification buildNotif(boolean active, long end) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, ArioLang.t(this, "قفل تمرکز", "Focus guard"), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = active
                ? ArioLang.t(this, "قفل برنامه‌ها فعال است تا ", "App lock is on until ") + hm(end)
                : ArioLang.t(this, "قفل تمرکز آماده است؛ با شروع پارت یا تایمر فعال می‌شود", "Focus guard is ready; it turns on with a part or the timer");
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(smallIcon())
                .setContentTitle(ArioLang.t(this, "قفل تمرکز Ario", "Ario focus guard"))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void setNotif(boolean active, long end) {
        int st = active ? 1 : 0;
        if (st == lastNotifState && end == lastNotifEnd) return;
        lastNotifState = st;
        lastNotifEnd = end;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NID, buildNotif(active, end));
        } catch (Throwable t) {
            // ignore
        }
    }
}
