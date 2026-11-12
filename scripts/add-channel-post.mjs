#!/usr/bin/env node
// Adds one post to the in-app "کانال Ario" (Ario Channel).
// Run it for EVERY visible change or new feature of the app, then `npm run build:web`.
//
//   node scripts/add-channel-post.mjs --title "عنوان" --text "توضیح" \
//        --img path/to/picture.(webp|png|jpg) --poll-q "سؤال نظرسنجی" --poll "گزینه ۱|گزینه ۲|گزینه ۳" \
//        [--kind new|imp|fix|info] [--ver v298] [--en-poll-q "Question"] [--en-poll "Option 1|Option 2"] \
//        [--en-title "Title"] [--en-text "Text"] [--ar-title "..."] [--ar-text "..."] [--date YYYY-MM-DD]
//
// EVERY post must have a picture of the feature (--img) AND a poll (--poll-q + --poll with 2 to 6 options split by |).
// The poll must be about THE TOPIC OF THAT POST's feature (not a generic question).
// The picture is copied to www/img/chn-<id>.<ext>. Use \n in --text for a line break.
// STICKERS IN THE TEXT: write [[st:ch-<name>]] inside --text (and --en-text) to put a moving sticker inside the sentence. They only work in channel post text
// (never alone, never in chats): ch-dancecat ch-pinkgirl ch-cool ch-kittens ch-beard ch-rat ch-shock ch-stare ch-blackcat.
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
if (!a.img) die('--img is required: every channel post must carry a picture (screenshot) of the feature.');
const opts = String(a.poll || '').split('|').map((x) => x.trim()).filter(Boolean);
if (!a['poll-q'] || opts.length < 2 || opts.length > 6) die('every channel post needs a poll: --poll-q "question" --poll "option 1|option 2" (2 to 6 options).');
const enOpts = a['en-poll'] ? String(a['en-poll']).split('|').map((x) => x.trim()).filter(Boolean) : null;
if (enOpts && enOpts.length !== opts.length) die('--en-poll must have the same number of options as --poll.');
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

let post = "{id:'" + id + "',d:'" + date + "'," + (a.ver ? 'v:' + q(a.ver) + ',' : '') + "k:'" + kind + "'," + (imgName ? "img:'" + imgName + "'," : '') + '\n t:' + q(a.title) + ',\n x:' + q(a.text) + ',\n pq:' + q(a['poll-q']) + ',po:' + JSON.stringify(opts);
if (a['en-title'] || a['en-text'] || enOpts) post += ',\n en:{t:' + q(a['en-title'] || a.title) + ',x:' + q(a['en-text'] || a.text) + (enOpts ? ',pq:' + q(a['en-poll-q'] || a['poll-q']) + ',po:' + JSON.stringify(enOpts) : '') + '}';
if (a['ar-title'] || a['ar-text']) post += ',\n ar:{t:' + q(a['ar-title'] || a.title) + ',x:' + q(a['ar-text'] || a.text) + '}';
post += '},\n';

src = src.replace(MARK, MARK + '\n' + post.trimEnd());
fs.writeFileSync(FILE, src);
console.log('Post added: ' + id + (imgName ? ' (picture www/img/' + imgName + ')' : '') + '\nNow run: npm run build:web');
