package __APPID__;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    /** A PDF that another app opened / shared with Ario; PdfViewerPlugin.takeShared() imports it into the library. */
    private static volatile Uri sharedPdf;

    static Uri takeSharedPdf() {
        Uri u = sharedPdf;
        sharedPdf = null;
        return u;
    }

    private void stashPdf(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(a)) u = i.getData();
        else if (Intent.ACTION_SEND.equals(a)) u = (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        sharedPdf = u;
        i.setAction(Intent.ACTION_MAIN); // so a rotation / recreate does not import it twice
        i.setData(null);
    }

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
        if (savedInstanceState == null) stashPdf(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        ArioNotifyCore.stashGo(this, intent);
        stashPdf(intent);
        super.onNewIntent(intent);
    }
}
