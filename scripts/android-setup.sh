#!/usr/bin/env bash
# Adds notification permissions and the notification icon to the generated Android project.
set -euo pipefail

MAN="android/app/src/main/AndroidManifest.xml"
RES="android/app/src/main/res"
if [ ! -f "$MAN" ]; then
  echo "Android project not found. Run: npx cap add android" >&2
  exit 1
fi

add_perm() {
  if ! grep -q "android.permission.$1" "$MAN"; then
    sed -i "s#<application#<uses-permission android:name=\"android.permission.$1\" />\n    <application#" "$MAN"
  fi
}
add_perm POST_NOTIFICATIONS
add_perm SCHEDULE_EXACT_ALARM
add_perm RECEIVE_BOOT_COMPLETED
add_perm VIBRATE
add_perm RECORD_AUDIO
add_perm CAMERA
add_perm MODIFY_AUDIO_SETTINGS

# Round video messages use the camera, but the app still installs on devices without one.
if ! grep -q 'android.hardware.camera' "$MAN"; then
  sed -i 's#<application#<uses-feature android:name="android.hardware.camera" android:required="false" />\n    <uses-feature android:name="android.hardware.camera.front" android:required="false" />\n    <application#' "$MAN"
fi

# Android 11+: let the app find the speech recognizer.
if ! grep -q 'android.speech.RecognitionService' "$MAN"; then
  sed -i 's#<application#<queries><intent><action android:name="android.speech.RecognitionService" /></intent><intent><action android:name="android.speech.action.RECOGNIZE_SPEECH" /></intent></queries>\n    <application#' "$MAN"
fi

# Android 11+: let the app find the text-to-speech engine (the Ario assistant talks).
if ! grep -q 'android.intent.action.TTS_SERVICE' "$MAN"; then
  sed -i 's#<queries>#<queries><intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>#' "$MAN"
fi

# Let other apps open / share a PDF with Ario ("Open with" and "Share"): the file is added to the library.
if ! grep -q 'application/pdf' "$MAN"; then
  sed -i '0,/<\/activity>/s#</activity>#    <intent-filter><action android:name="android.intent.action.VIEW" /><category android:name="android.intent.category.DEFAULT" /><category android:name="android.intent.category.BROWSABLE" /><data android:mimeType="application/pdf" /></intent-filter>\n        <intent-filter><action android:name="android.intent.action.SEND" /><category android:name="android.intent.category.DEFAULT" /><data android:mimeType="application/pdf" /></intent-filter>\n    </activity>#' "$MAN"
fi

# Make sure the app (and its notifications) can lay out right-to-left.
if ! grep -q 'android:supportsRtl' "$MAN"; then
  sed -i 's#<application#<application android:supportsRtl="true"#' "$MAN"
else
  sed -i 's#android:supportsRtl="false"#android:supportsRtl="true"#' "$MAN"
fi

mkdir -p "$RES/drawable"
cat > "$RES/drawable/ic_stat_barnameyar.xml" <<'XML'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
  <path android:fillColor="#FFFFFFFF"
      android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"/>
</vector>
XML
# Big app icon shown inside notifications.
mkdir -p "$RES/drawable-nodpi"
cp icons/mipmap-xxhdpi/ic_launcher.png "$RES/drawable-nodpi/ic_notif_large.png"
echo "Android notification setup applied."

# Fail the build loudly if the microphone permission did not make it into the manifest.
grep -q "android.permission.RECORD_AUDIO" "$MAN" || { echo "RECORD_AUDIO missing from manifest" >&2; exit 1; }
grep -q "android.permission.CAMERA" "$MAN" || { echo "CAMERA missing from manifest" >&2; exit 1; }
