#!/usr/bin/env bash
# Adds the home-screen widget (native Java + resources) to the generated Android project.
set -euo pipefail

MAN="android/app/src/main/AndroidManifest.xml"
if [ ! -f "$MAN" ]; then
  echo "Android project not found. Run: npx cap add android" >&2
  exit 1
fi

APPID=$(python3 -c "import json;print(json.load(open('capacitor.config.json'))['appId'])")
JDIR="android/app/src/main/java/$(echo "$APPID" | tr . /)"
mkdir -p "$JDIR"
for f in ArioLang MainActivity WidgetBridgePlugin ArioWidgetProvider AppLockPlugin ImageSavePlugin PdfViewerPlugin FocusNotifyPlugin FocusGuardPlugin FocusGuardService ArioNotifyPlugin ArioNotifyCore ArioNotifyReceiver ArioTtsPlugin ArioAudioPlugin ArioCounselorWorker ArioChatNotify; do
  sed "s/__APPID__/$APPID/g" "native/java/$f.java" > "$JDIR/$f.java"
done
cp -r native/res/. android/app/src/main/res/

if ! grep -q "ArioWidgetProvider" "$MAN"; then
  python3 - "$MAN" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf8").read()
rec = '''
        <receiver android:name=".ArioWidgetProvider" android:exported="true" android:label="Ario">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/ario_widget_info" />
        </receiver>
    '''
s = s.replace("</application>", rec + "</application>", 1)
open(p, "w", encoding="utf8").write(s)
PY
fi
if ! grep -q "ArioNotifyReceiver" "$MAN"; then
  python3 - "$MAN" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf8").read()
rec = '''
        <receiver android:name=".ArioNotifyReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    '''
s = s.replace("</application>", rec + "</application>", 1)
open(p, "w", encoding="utf8").write(s)
PY
fi
# Biometric prompt library (used by the app lock)
GR="android/app/build.gradle"
if [ -f "$GR" ] && ! grep -q "androidx.biometric" "$GR"; then
  sed -i '/^dependencies {/a\    implementation "androidx.biometric:biometric:1.1.0"' "$GR"
fi
# WorkManager: the advisor's background check for new reports (works while the app is closed)
if [ -f "$GR" ] && ! grep -q "androidx.work:work-runtime" "$GR"; then
  sed -i '/^dependencies {/a\    implementation "androidx.work:work-runtime:2.9.1"' "$GR"
fi

# Focus guard: blocks/warns about distracting apps during a study part (Usage access + Display over other apps)
if ! grep -q "FocusGuardService" "$MAN"; then
  python3 - "$MAN" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf8").read()
if "xmlns:tools" not in s:
    s = s.replace("<manifest ", '<manifest xmlns:tools="http://schemas.android.com/tools" ', 1)
perms = ""
for name, extra in (("FOREGROUND_SERVICE", ""), ("FOREGROUND_SERVICE_SPECIAL_USE", ""),
                    ("PACKAGE_USAGE_STATS", ' tools:ignore="ProtectedPermissions"'), ("SYSTEM_ALERT_WINDOW", "")):
    if "android.permission." + name + '"' not in s:
        perms += '    <uses-permission android:name="android.permission.' + name + '"' + extra + ' />\n'
if "ario-guard-queries" not in s:
    q = '<!-- ario-guard-queries --><intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent>'
    if "<queries>" in s:
        s = s.replace("<queries>", "<queries>" + q, 1)
    else:
        perms += "    <queries>" + q + "</queries>\n"
svc = """
        <service
            android:name=".FocusGuardService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="focus_app_guard" />
        </service>
    """
s = s.replace("<application", perms + "    <application", 1)
s = s.replace("</application>", svc + "</application>", 1)
open(p, "w", encoding="utf8").write(s)
PY
fi
echo "Widget and app lock added."
