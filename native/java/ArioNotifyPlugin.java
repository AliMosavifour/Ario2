package __APPID__;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

/** Bridge for Ario's themed notifications (light / dark / profile banner background). */
@CapacitorPlugin(name = "ArioNotify")
public class ArioNotifyPlugin extends Plugin {

    @PluginMethod
    public void setStyle(PluginCall call) {
        ArioNotifyCore.saveStyle(getContext(), call.getString("theme", "sys"), call.getString("banner", ""), call.getString("avatar", ""), call.getString("opts", "{}"));
        call.resolve();
    }

    @PluginMethod
    public void replaceAll(PluginCall call) {
        JSONArray items = call.getData().optJSONArray("items");
        JSONArray keepArr = call.getData().optJSONArray("keep");
        Set<Integer> keep = new HashSet<>();
        for (int i = 0; keepArr != null && i < keepArr.length(); i++) keep.add(keepArr.optInt(i));
        ArioNotifyCore.replaceAll(getContext(), items, keep);
        call.resolve();
    }

    @PluginMethod
    public void add(PluginCall call) {
        ArioNotifyCore.add(getContext(), call.getData().optJSONArray("items"));
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        ArioNotifyCore.cancel(getContext(), call.getData().optJSONArray("ids"));
        call.resolve();
    }

    /** Pinned (always-there) notification: on = show it with the given snapshot, off = remove it. Later snapshots arrive as timed items (kind "pin"). */
    @PluginMethod
    public void pin(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        android.content.Context c = getContext();
        ArioNotifyCore.sp(c).edit().putBoolean(ArioNotifyCore.K_PINON, on).apply();
        if (on) {
            JSObject now = call.getObject("now");
            if (now != null) ArioNotifyCore.pinPost(c, now);
        } else {
            ArioNotifyCore.pinClear(c);
        }
        call.resolve();
    }

    /** Section to open after the person tapped a notification ("" when the app was opened normally). */
    @PluginMethod
    public void takeGo(PluginCall call) {
        JSObject res = new JSObject();
        res.put("go", ArioNotifyCore.takeGo(getContext()));
        call.resolve(res);
    }

    @PluginMethod
    public void takeActions(PluginCall call) {
        JSObject res = new JSObject();
        res.put("actions", ArioNotifyCore.takeActions(getContext()));
        call.resolve(res);
    }

    /* ---------- advisor: phone notice about new reports while the app is closed (see ArioCounselorWorker) ---------- */
    /** The web app hands over the sign-in so the background check can run; on=false switches it off and forgets the sign-in. */
    @PluginMethod
    public void cwSet(PluginCall call) {
        try {
            ArioCounselorWorker.setSession(getContext(), call.getData());
        } catch (Throwable t) {
            // ignore
        }
        call.resolve();
    }

    /** The app is on screen: the background check stays quiet for a few minutes. */
    @PluginMethod
    public void cwFg(PluginCall call) {
        ArioCounselorWorker.foreground(getContext());
        call.resolve();
    }

    /** The web app announced reports itself up to this time (ms). */
    @PluginMethod
    public void cwMark(PluginCall call) {
        ArioCounselorWorker.marked(getContext(), call.getData().optLong("rn", 0));
        call.resolve();
    }

    /** Tokens the background check renewed (to adopt) and the newest report time it announced. */
    @PluginMethod
    public void cwTake(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(ArioCounselorWorker.takePending(getContext())));
        } catch (Throwable t) {
            call.resolve(new JSObject());
        }
    }
}
