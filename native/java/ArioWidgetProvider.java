package __APPID__;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget: today's progress and the next open tasks. */
public class ArioWidgetProvider extends AppWidgetProvider {
    static final String PREFS = "ario_widget";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(ctx));
    }

    public static void refreshAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, ArioWidgetProvider.class));
        for (int id : ids) m.updateAppWidget(id, build(ctx));
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_ario);
        int[] rows = {R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4};
        String title = ArioLang.t(ctx, "امروز", "Today");
        String sub = ArioLang.t(ctx, "برای دیدن کارها، برنامه را یک بار باز کن", "Open the app once to see your tasks");
        String over = "";
        String[] lines = new String[4];
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", null);
            if (raw != null) {
                JSONObject o = new JSONObject(raw);
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                if (today.equals(o.optString("d"))) {
                    title = o.optString("title", title);
                    sub = o.optString("sub", sub);
                    over = o.optString("over", "");
                    JSONArray items = o.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length() && i < 4; i++) {
                            JSONObject it = items.getJSONObject(i);
                            String m = it.optString("m", "");
                            lines[i] = "○  " + it.optString("t", "") + (m.isEmpty() ? "" : "  ·  " + m);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        v.setTextViewText(R.id.w_title, title);
        v.setTextViewText(R.id.w_sub, sub);
        v.setTextViewText(R.id.w_over, over);
        v.setViewVisibility(R.id.w_over, over.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < 4; i++) {
            if (lines[i] == null) {
                v.setViewVisibility(rows[i], View.GONE);
            } else {
                v.setTextViewText(rows[i], lines[i]);
                v.setViewVisibility(rows[i], View.VISIBLE);
            }
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, open, flags));
        return v;
    }
}
