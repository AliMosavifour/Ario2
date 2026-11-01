package __APPID__;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Ongoing focus-timer notification: big live countdown in the notification shade (works with the screen off). */
@CapacitorPlugin(name = "FocusNotify")
public class FocusNotifyPlugin extends Plugin {
    private static final String CH = "focus_timer";
    private static final int ID = 2100000800;

    private void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) != null) return;
        NotificationChannel ch = new NotificationChannel(CH, ArioLang.t(c, "تایمر تمرکز", "Focus timer"), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(ArioLang.t(c, "شمارش معکوس زندهٔ تمرکز", "Live focus countdown"));
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @PluginMethod
    public void show(PluginCall call) {
        try {
            Context c = getContext();
            ensureChannel(c);
            boolean run = Boolean.TRUE.equals(call.getBoolean("run", false));
            long end = call.getData().optLong("end", 0L);
            String title = call.getString("title", ArioLang.t(c, "تمرکز", "Focus"));
            String text = call.getString("text", "");
            String sub = call.getString("sub", "");
            String leftText = call.getString("leftText", "");

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.notif_focus);
            v.setTextViewText(R.id.nc_title, title);
            v.setTextViewText(R.id.nc_text, text);
            boolean themed = ArioNotifyCore.paint(c, v, Color.parseColor(run ? "#FF375F" : "#8E8E93"));
            if (themed) {
                v.setTextColor(R.id.nf_time, ArioNotifyCore.fgColor(c));
                v.setTextColor(R.id.nf_static, ArioNotifyCore.fgColor(c));
            }
            if (run && end > 0) {
                long remain = Math.max(0L, end - System.currentTimeMillis());
                v.setViewVisibility(R.id.nf_static, View.GONE);
                v.setViewVisibility(R.id.nf_time, View.VISIBLE);
                v.setChronometer(R.id.nf_time, SystemClock.elapsedRealtime() + remain, null, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) v.setChronometerCountDown(R.id.nf_time, true);
            } else {
                v.setViewVisibility(R.id.nf_time, View.GONE);
                v.setViewVisibility(R.id.nf_static, View.VISIBLE);
                v.setTextViewText(R.id.nf_static, leftText);
            }

            int small = c.getResources().getIdentifier("ic_stat_barnameyar", "drawable", c.getPackageName());
            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(small != 0 ? small : android.R.drawable.ic_lock_idle_alarm)
                    .setColor(Color.parseColor(run ? "#FF375F" : "#8E8E93"))
                    .setColorized(false)
                    .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(v)
                    .setSubText(sub)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            Bitmap lbm = ArioNotifyCore.largeIcon(c);
            if (lbm != null) b.setLargeIcon(lbm);
            if (run && end > 0) b.setTimeoutAfter(Math.max(1000L, end - System.currentTimeMillis() + 1500L));
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(ID, b.build());
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void clear(PluginCall call) {
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(ID);
        call.resolve();
    }
}
