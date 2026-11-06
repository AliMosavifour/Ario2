package __APPID__;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WidgetBridgePlugin.class);
        registerPlugin(AppLockPlugin.class);
        registerPlugin(ImageSavePlugin.class);
        registerPlugin(PdfViewerPlugin.class);
        registerPlugin(FocusNotifyPlugin.class);
        registerPlugin(FocusGuardPlugin.class);
        registerPlugin(ArioNotifyPlugin.class);
        registerPlugin(ArioTtsPlugin.class);
        super.onCreate(savedInstanceState);
        // opened by tapping a notification: remember which section it was for
        ArioNotifyCore.stashGo(this, getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        ArioNotifyCore.stashGo(this, intent);
        super.onNewIntent(intent);
    }
}
