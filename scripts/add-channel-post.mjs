#!/usr/bin/env node
// Adds one post to the in-app "کانال Ario" (Ario Channel).
// Run it for EVERY visible change or new feature of the app, then `npm run build:web`.
//
//   node scripts/add-channel-post.mjs --title "عنوان" --text "توضیح" \
//        [--kind new|imp|fix|info] [--img path/to/picture.(webp|png|jpg)] [--ver v298] \
//        [--en-title "Title"] [--en-text "Text"] [--ar-title "..."] [--ar-text "..."] [--date YYYY-MM-DD]
//
// The picture is copied to www/img/chn-<id>.<ext>. Use \n in --text for a line break.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = path.join(root, 'src/js/channel-posts.js');
const MARK = '/*POSTS*/';

const a = {};
for (let i = 2; i < process.argv.length; i++) {
  const k = process.argv[i];
  if (k.startsWith('--')) a[k.slice(2)] = process.argv[++i];
}
const die = (m) => { console.error('ERROR: ' + m); process.exit(1); };
if (!a.title || !a.text) die('--title and --text are required (see the top of this file).');
const kind = a.kind || 'new';
if (!['new', 'imp', 'fix', 'info'].includes(kind)) die('--kind must be new, imp, fix or info');

const nl = (s) => String(s).replace(/\\n/g, '\n');
const q = (s) => JSON.stringify(nl(s));            // valid JS string literal, handles quotes and \n
const date = a.date || new Date().toISOString().slice(0, 10);
const id = a.id || (date + '-' + Math.random().toString(36).slice(2, 6));

let src = fs.readFileSync(FILE, 'utf8');
if (!src.includes(MARK)) die('marker ' + MARK + ' not found in ' + FILE);
if (src.includes("id:'" + id + "'")) die('a post with this id already exists');

let imgName = '';
if (a.img) {
  if (!fs.existsSync(a.img)) die('picture not found: ' + a.img);
  const ext = path.extname(a.img).toLowerCase().replace('.jpeg', '.jpg');
  if (!['.webp', '.png', '.jpg'].includes(ext)) die('picture must be .webp, .png or .jpg');
  imgName = 'chn-' + id + ext;
  fs.copyFileSync(a.img, path.join(root, 'www/img', imgName));
}

let post = "{id:'" + id + "',d:'" + date + "'," + (a.ver ? 'v:' + q(a.ver) + ',' : '') + "k:'" + kind + "'," + (imgName ? "img:'" + imgName + "'," : '') + '\n t:' + q(a.title) + ',\n x:' + q(a.text);
if (a['en-title'] || a['en-text']) post += ',\n en:{t:' + q(a['en-title'] || a.title) + ',x:' + q(a['en-text'] || a.text) + '}';
if (a['ar-title'] || a['ar-text']) post += ',\n ar:{t:' + q(a['ar-title'] || a.title) + ',x:' + q(a['ar-text'] || a.text) + '}';
post += '},\n';

src = src.replace(MARK, MARK + '\n' + post.trimEnd());
fs.writeFileSync(FILE, src);
console.log('Post added: ' + id + (imgName ? ' (picture www/img/' + imgName + ')' : '') + '\nNow run: npm run build:web');
