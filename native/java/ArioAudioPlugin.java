package __APPID__;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the voice chat sound comes out: phone earpiece, loudspeaker, a Bluetooth headset or a wired headset.
 * routes() lists what is connected right now and which one is in use; setRoute({route}) switches; release() gives the control back.
 */
@CapacitorPlugin(name = "ArioAudio")
public class ArioAudioPlugin extends Plugin {

    private boolean touched = false;

    private AudioManager am() {
        return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    private static String kindOf(int type) {
        if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return "phone";
        if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "speaker";
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return "bluetooth";
        if (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return "bluetooth";
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_USB_HEADSET) return "wired";
        return null;
    }

    private static String nameOf(AudioDeviceInfo d) {
        try {
            CharSequence n = d.getProductName();
            return n == null ? "" : n.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** the devices that can carry a call right now, one per kind */
    private List<AudioDeviceInfo> devices() {
        List<AudioDeviceInfo> out = new ArrayList<>();
        AudioManager a = am();
        if (a == null || Build.VERSION.SDK_INT < 23) return out;
        List<AudioDeviceInfo> src = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            src.addAll(a.getAvailableCommunicationDevices());
        } else {
            for (AudioDeviceInfo d : a.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) src.add(d);
        }
        List<String> seen = new ArrayList<>();
        for (AudioDeviceInfo d : src) {
            String k = kindOf(d.getType());
            if (k == null || seen.contains(k)) continue;
            // a Bluetooth music-only (A2DP) device cannot carry a call before Android 12; the SCO one can
            if (Build.VERSION.SDK_INT < 31 && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) continue;
            seen.add(k);
            out.add(d);
        }
        return out;
    }

    private String current() {
        AudioManager a = am();
        if (a == null) return "speaker";
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo d = a.getCommunicationDevice();
            if (d != null) {
                String k = kindOf(d.getType());
                if (k != null) return k;
            }
        }
        if (a.isBluetoothScoOn()) return "bluetooth";
        if (a.isSpeakerphoneOn()) return "speaker";
        if (a.isWiredHeadsetOn()) return "wired";
        return touched ? "phone" : "speaker";
    }

    @PluginMethod
    public void routes(PluginCall call) {
        try {
            JSArray arr = new JSArray();
            boolean hasPhone = false, hasSpeaker = false;
            for (AudioDeviceInfo d : devices()) {
                String k = kindOf(d.getType());
                JSObject o = new JSObject();
                o.put("id", k);
                o.put("name", nameOf(d));
                arr.put(o);
                if ("phone".equals(k)) hasPhone = true;
                if ("speaker".equals(k)) hasSpeaker = true;
            }
            // very old phones do not list devices: the earpiece and the loudspeaker are always there
            if (!hasSpeaker) { JSObject o = new JSObject(); o.put("id", "speaker"); o.put("name", ""); arr.put(o); }
            if (!hasPhone && getContext().getPackageManager().hasSystemFeature("android.hardware.telephony")) {
                JSObject o = new JSObject(); o.put("id", "phone"); o.put("name", ""); arr.put(o);
            }
            JSObject res = new JSObject();
            res.put("routes", arr);
            res.put("current", current());
            call.resolve(res);
        } catch (Throwable t) {
            call.reject(String.valueOf(t.getMessage()));
        }
    }

    @PluginMethod
    public void setRoute(PluginCall call) {
        String route = call.getString("route", "speaker");
        AudioManager a = am();
        if (a == null) { call.reject("no audio"); return; }
        try {
            touched = true;
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : a.getAvailableCommunicationDevices()) {
                    if (route.equals(kindOf(d.getType()))) { pick = d; break; }
                }
                if (pick == null) { call.reject("not available"); return; }
                if (!a.setCommunicationDevice(pick)) { call.reject("refused"); return; }
            } else {
                a.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if ("bluetooth".equals(route)) {
                    a.setSpeakerphoneOn(false);
                    a.startBluetoothSco();
                    a.setBluetoothScoOn(true);
                } else {
                    try { a.stopBluetoothSco(); } catch (Throwable ignore) { }
                    a.setBluetoothScoOn(false);
                    a.setSpeakerphoneOn("speaker".equals(route));
                }
            }
            JSObject res = new JSObject();
            res.put("current", current());
            call.resolve(res);
        } catch (Throwable t) {
            call.reject(String.valueOf(t.getMessage()));
        }
    }

    @PluginMethod
    public void release(PluginCall call) {
        AudioManager a = am();
        try {
            if (a != null && touched) {
                if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice();
                else {
                    try { a.stopBluetoothSco(); } catch (Throwable ignore) { }
                    a.setBluetoothScoOn(false);
                    a.setSpeakerphoneOn(false);
                }
            }
        } catch (Throwable ignore) { }
        touched = false;
        call.resolve();
    }
}
