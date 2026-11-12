#!/usr/bin/env node
// Build guard: every post of the Ario Channel from REQUIRED_FROM on must have a picture (that exists in www/img) AND a poll (2-6 options).
// Older posts (before the rule) are left as they are. Run automatically by scripts/build-web.sh.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REQUIRED_FROM = '2026-11-11';
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = fs.readFileSync(path.join(root, 'src/js/channel-posts.js'), 'utf8');
const posts = new Function('__AR', src + '\n;return CHAN_POSTS;')({});
const bad = [];
for (const p of posts) {
  if (!p.d || p.d < REQUIRED_FROM) continue;
  if (!p.img) bad.push(p.id + ': no picture (--img)');
  else if (!fs.existsSync(path.join(root, 'www/img', p.img))) bad.push(p.id + ': picture file missing in www/img: ' + p.img);
  if (!p.pq || !Array.isArray(p.po) || p.po.length < 2 || p.po.length > 6) bad.push(p.id + ': no poll (--poll-q / --poll with 2-6 options)');
  if (p.en && p.en.po && (!Array.isArray(p.en.po) || p.en.po.length !== (p.po || []).length)) bad.push(p.id + ': English poll options count differs');
}
// Stickers inside post text: only the channel-only ones ([[st:ch-<name>]]) and their file must exist in www/img/st
for (const p of posts) {
  for (const o of [p, p.en, p.ar]) {
    if (!o || typeof o.x !== 'string') continue;
    for (const m of o.x.matchAll(/\[\[st:([a-z0-9-]{3,40})\]\]/gi)) {
      const id = m[1].toLowerCase();
      if (!/^ch-[a-z0-9]+$/.test(id)) bad.push(p.id + ': [[st:' + m[1] + ']] is not a channel sticker (use ch-<name>)');
      else if (!fs.existsSync(path.join(root, 'www/img/st', id + '.webm'))) bad.push(p.id + ': sticker file missing: www/img/st/' + id + '.webm');
    }
  }
}
if (bad.length) { console.error('Ario Channel rule broken (every post needs a picture and a poll):\n - ' + bad.join('\n - ')); process.exit(1); }
console.log('channel posts OK (' + posts.length + ' posts)');
