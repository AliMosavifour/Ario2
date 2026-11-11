#!/usr/bin/env bash
# Builds the small, fast web files in www/ from the readable sources in src/.
# Edit src/js/app.js and src/css/app.css, then run:  npm run build:web
set -euo pipefail
cd "$(dirname "$0")/.."
ESB="npx --no-install esbuild"
node scripts/check-channel-posts.mjs   # every channel post needs a picture and a poll
# English and Arabic dictionaries + language core + Konkoria English texts + Arabic screen-text translator + the app are joined into one script
# channel-posts.js = the posts of the in-app Ario Channel (see scripts/add-channel-post.mjs)
# names are kept (no --minify-identifiers) so an error shown on screen says which function failed
cat src/js/i18n-en.js src/js/i18n-ar.js src/js/i18n.js src/js/i18n-kk.js src/js/i18n-ar-dom.js src/js/think.js src/js/channel-posts.js src/js/emoji-data.js src/js/app.js | $ESB --minify-whitespace --minify-syntax --target=chrome80 --legal-comments=none --charset=utf8 > www/js/app.js
$ESB src/css/app.css --minify --target=chrome80 --legal-comments=none --charset=utf8 --outfile=www/css/app.css

# ---- pdf-lib (cuts pages out of a PDF: "جدا کردن صفحه‌ها") goes into www/lib; the browser build falls back to a CDN ----
mkdir -p www/lib
if [ -f node_modules/pdf-lib/dist/pdf-lib.min.js ]; then cp node_modules/pdf-lib/dist/pdf-lib.min.js www/lib/pdf-lib.min.js; else echo "WARNING: pdf-lib is not installed (run: npm install); page extraction will need the internet" >&2; fi

# ---- Offline text recognition for "اسکن عکس جدول": engine + Persian language file go into www/ocr ----
OCR=www/ocr
rm -rf "$OCR"; mkdir -p "$OCR/lang"
fail() { echo "ERROR (table scan): $1" >&2; exit 1; }

[ -f node_modules/tesseract.js/dist/tesseract.min.js ] || fail "tesseract.js is not installed. Run: npm install"
cp node_modules/tesseract.js/dist/tesseract.min.js node_modules/tesseract.js/dist/worker.min.js "$OCR/"

CORE=$(ls node_modules/tesseract.js-core/tesseract-core*lstm*.js 2>/dev/null || true)
[ -n "$CORE" ] || CORE=$(ls node_modules/tesseract.js-core/tesseract-core*.js 2>/dev/null || true)
[ -n "$CORE" ] || fail "tesseract.js-core files were not found in node_modules"
for f in $CORE; do cp "$f" "$OCR/"; done

# Persian language file: 1) GitHub (tessdata_fast)  2) the npm package @tesseract.js-data/fas
if curl -fL --retry 3 --max-time 120 -o "$OCR/lang/fas.traineddata" "https://github.com/tesseract-ocr/tessdata_fast/raw/main/fas.traineddata" && [ -s "$OCR/lang/fas.traineddata" ]; then
  gzip -f -9 "$OCR/lang/fas.traineddata"
else
  echo "GitHub download failed, trying the npm package..." >&2
  rm -f "$OCR/lang/fas.traineddata"
  npm install --no-save --no-audit --no-fund @tesseract.js-data/fas >/dev/null 2>&1 || true
  SRC=$(find node_modules/@tesseract.js-data/fas -name 'fas.traineddata.gz' 2>/dev/null | sort | grep -m1 'best_int' || true)
  [ -n "$SRC" ] || SRC=$(find node_modules/@tesseract.js-data/fas -name 'fas.traineddata.gz' 2>/dev/null | sort | head -n1 || true)
  [ -n "$SRC" ] || fail "could not get the Persian language file (fas.traineddata)"
  cp "$SRC" "$OCR/lang/fas.traineddata.gz"
fi
[ -s "$OCR/lang/fas.traineddata.gz" ] || fail "fas.traineddata.gz is empty"
echo "OCR files:"; ls -la "$OCR" "$OCR/lang"
echo "web files built"
