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
        ArioNotifyCore.saveStyle(getContext(), call.getString("theme", "sys"), call.getString("banner", ""), call.getString("avatar", ""));
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

    @PluginMethod
    public void takeActions(PluginCall call) {
        JSObject res = new JSObject();
        res.put("actions", ArioNotifyCore.takeActions(getContext()));
        call.resolve(res);
    }
}
