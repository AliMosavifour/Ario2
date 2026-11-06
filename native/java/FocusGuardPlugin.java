package __APPID__;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Focus guard settings from the web app: which apps to guard, when, and the permissions it needs. */
@CapacitorPlugin(name = "FocusGuard")
public class FocusGuardPlugin extends Plugin {

    @PluginMethod
    public void status(PluginCall call) {
        Context c = getContext();
        JSObject o = new JSObject();
        o.put("usage", FocusGuardService.hasUsageAccess(c));
        o.put("overlay", FocusGuardService.canOverlay(c));
        o.put("on", c.getSharedPreferences(FocusGuardService.PREFS, Context.MODE_PRIVATE).getBoolean("on", false));
        call.resolve(o);
    }

    @PluginMethod
    public void openUsageSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Apps with a launcher icon (except Ario), sorted by name, so the user can pick the distracting ones. */
    @PluginMethod
    public void apps(PluginCall call) {
        try {
            Context c = getContext();
            PackageManager pm = c.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            final List<String[]> rows = new ArrayList<String[]>();
            Set<String> seen = new HashSet<String>();
            for (ResolveInfo r : list) {
                String pk = r.activityInfo.packageName;
                if (pk.equals(c.getPackageName()) || !seen.add(pk)) continue;
                rows.add(new String[]{pk, String.valueOf(r.loadLabel(pm))});
            }
            Collections.sort(rows, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return a[1].compareToIgnoreCase(b[1]);
                }
            });
            JSArray arr = new JSArray();
            for (String[] r : rows) {
                JSObject o = new JSObject();
                o.put("pk", r[0]);
                o.put("label", r[1]);
                arr.put(o);
            }
            JSObject res = new JSObject();
            res.put("apps", arr);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /**
     * on: guard enabled; mode: "warn" | "block"; pk: packages to guard; sn: allow the 5-minute break;
     * wins: [[startMs, endMs], ...] timetable parts; te: end of the running focus timer (0 = none);
     * title/msg/back/rest/warn: texts shown on the block screen and in the warning.
     */
    @PluginMethod
    public void config(PluginCall call) {
        try {
            Context c = getContext();
            SharedPreferences.Editor e = c.getSharedPreferences(FocusGuardService.PREFS, Context.MODE_PRIVATE).edit();
            boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
            e.putBoolean("on", on);
            if (on) {
                e.putString("mode", "block".equals(call.getString("mode", "warn")) ? "block" : "warn");
                e.putBoolean("sn", !Boolean.FALSE.equals(call.getBoolean("sn", true)));
                e.putLong("te", call.getData().optLong("te", 0L));
                Set<String> pks = new HashSet<String>();
                JSONArray pa = call.getData().optJSONArray("pk");
                if (pa != null) for (int i = 0; i < pa.length(); i++) pks.add(pa.getString(i));
                e.putStringSet("pk", pks);
                JSONArray w = call.getData().optJSONArray("wins");
                e.putString("wins", w != null ? w.toString() : "[]");
                String[] keys = {"title", "msg", "back", "rest", "warn"};
                for (String k : keys) {
                    String v = call.getString(k);
                    if (v != null && v.length() > 0) e.putString("t_" + k, v);
                }
            }
            e.apply();
            if (on) {
                FocusGuardService.ensure(c);
            } else {
                c.stopService(new Intent(c, FocusGuardService.class));
            }
            call.resolve();
        } catch (Exception ex) {
            call.reject(String.valueOf(ex.getMessage()));
        }
    }
}
