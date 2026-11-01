// One-time download of the offline text-recognition files into www/ocr (needs internet once).
// Run on your PC:   node scripts/get-ocr.mjs
// After this the scan works with NO internet, also inside the APK (commit www/ocr to GitHub).
import { mkdir, writeFile } from 'node:fs/promises';
const J = 'https://cdn.jsdelivr.net/npm/';
const files = [
  [J + 'tesseract.js@5.1.1/dist/tesseract.min.js', 'tesseract.min.js'],
  [J + 'tesseract.js@5.1.1/dist/worker.min.js', 'worker.min.js'],
  [J + 'tesseract.js-core@5.1.1/tesseract-core-lstm.wasm.js', 'tesseract-core-lstm.wasm.js'],
  [J + 'tesseract.js-core@5.1.1/tesseract-core-simd-lstm.wasm.js', 'tesseract-core-simd-lstm.wasm.js'],
  [J + '@tesseract.js-data/fas/4.0.0_best_int/fas.traineddata.gz', 'lang/fas.traineddata.gz'],
];
await mkdir('www/ocr/lang', { recursive: true });
for (const [url, name] of files) {
  const r = await fetch(url);
  if (!r.ok) { console.error('FAILED', r.status, url); process.exit(1); }
  const b = Buffer.from(await r.arrayBuffer());
  await writeFile('www/ocr/' + name, b);
  console.log('ok', name, b.length);
}
