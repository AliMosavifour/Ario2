#!/usr/bin/env bash
# Copies the Ario app icons into the generated Android project and points the manifest at them.
# The icon uses its own resource names (ic_ario*) so the default Capacitor icon can never win.
set -euo pipefail

RES="android/app/src/main/res"
MAN="android/app/src/main/AndroidManifest.xml"
if [ ! -d "$RES" ] || [ ! -f "$MAN" ]; then
  echo "Android project not found. Run: npx cap add android" >&2
  exit 1
fi

# Remove every default launcher icon file that Capacitor generated (png, webp, xml).
find "$RES" -type f \( -name "ic_launcher*.png" -o -name "ic_launcher*.webp" -o -name "ic_launcher*.xml" \) -delete

for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$RES/mipmap-$d"
  cp icons/mipmap-$d/ic_launcher.png            "$RES/mipmap-$d/ic_ario.png"
  cp icons/mipmap-$d/ic_launcher_round.png      "$RES/mipmap-$d/ic_ario_round.png"
  cp icons/mipmap-$d/ic_launcher_foreground.png "$RES/mipmap-$d/ic_ario_foreground.png"
done

mkdir -p "$RES/values" "$RES/mipmap-anydpi-v26"
cat > "$RES/values/ic_ario_background.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_ario_background">#727BF5</color>
</resources>
XML
for n in ic_ario ic_ario_round; do
cat > "$RES/mipmap-anydpi-v26/$n.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_ario_background"/>
    <foreground android:drawable="@mipmap/ic_ario_foreground"/>
</adaptive-icon>
XML
done

# Point the manifest at the new icon.
python3 - "$MAN" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf8").read()
m = re.search(r"<application\b[^>]*>", s)
tag = m.group(0)
new = re.sub(r'\s+android:(icon|roundIcon)="[^"]*"', "", tag)
new = new.replace("<application", '<application android:icon="@mipmap/ic_ario" android:roundIcon="@mipmap/ic_ario_round"', 1)
s = s.replace(tag, new, 1)
open(p, "w", encoding="utf8").write(s)
PY
grep -q 'android:icon="@mipmap/ic_ario"' "$MAN" || { echo "Could not set the app icon in the manifest" >&2; exit 1; }
echo "Icons applied."
