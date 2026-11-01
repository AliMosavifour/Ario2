package __APPID__;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Fires scheduled Ario notifications, handles the snooze / done buttons, and re-arms alarms after a reboot. */
public class ArioNotifyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        try {
            if ("ario.fire".equals(a)) {
                JSONObject it = ArioNotifyCore.pop(c, i.getIntExtra("id", 0));
                if (it != null) ArioNotifyCore.post(c, it);
            } else if ("ario.snooze".equals(a)) {
                int id = i.getIntExtra("id", 0);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(id);
                String json = i.getStringExtra("item");
                if (json != null) {
                    JSONObject it = new JSONObject(json);
                    long at = System.currentTimeMillis() + 600000L;
                    it.put("at", at);
                    org.json.JSONArray arr = new org.json.JSONArray();
                    arr.put(it);
                    ArioNotifyCore.add(c, arr);
                    ArioNotifyCore.addAction(c, "snooze", i.getStringExtra("tid"), at);
                }
            } else if ("ario.done".equals(a)) {
                int id = i.getIntExtra("id", 0);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(id);
                ArioNotifyCore.addAction(c, "done", i.getStringExtra("tid"), System.currentTimeMillis());
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                ArioNotifyCore.rescheduleAll(c);
            }
        } catch (Throwable t) {
            // ignore
        }
    }
}
