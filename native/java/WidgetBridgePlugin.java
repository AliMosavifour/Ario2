package __APPID__;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Receives today's summary from the web app and refreshes the home-screen widget. */
@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {
    /** Remembers the app language (fa / en) for notification buttons, channel names and widget fallbacks. */
    @PluginMethod
    public void setLang(PluginCall call) {
        Context c = getContext();
        ArioLang.set(c, call.getString("lang", "fa"));
        try { ArioNotifyCore.ensureChannels(c); } catch (Exception e) { /* best effort */ }
        ArioWidgetProvider.refreshAll(c);
        call.resolve();
    }

    /** Paints the system status bar and navigation bar (and the window behind them) with the app background, so no white strip shows in dark mode. */
    @PluginMethod
    public void setBars(final PluginCall call) {
        final String hex = call.getString("color", "#e8edf8");
        final boolean dark = Boolean.TRUE.equals(call.getBoolean("dark", false));
        final Activity a = getActivity();
        if (a == null) { call.resolve(); return; }
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    int col = Color.parseColor(hex);
                    Window w = a.getWindow();
                    w.getDecorView().setBackgroundColor(col);
                    w.setStatusBarColor(col);
                    w.setNavigationBarColor(col);
                    WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
                    if (c != null) {
                        c.setAppearanceLightStatusBars(!dark);
                        c.setAppearanceLightNavigationBars(!dark);
                    }
                } catch (Exception e) { /* best effort */ }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void update(PluginCall call) {
        String json = call.getString("json", "{}");
        Context c = getContext();
        c.getSharedPreferences(ArioWidgetProvider.PREFS, Context.MODE_PRIVATE)
                .edit().putString("data", json).apply();
        ArioWidgetProvider.refreshAll(c);
        call.resolve();
    }
}
