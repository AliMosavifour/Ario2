#!/usr/bin/env python3
"""Replaces the Noto emoji sheets in www/img/em with Microsoft Fluent Emoji (3D, MIT licence) while the APK is built.
Usage: python3 scripts/fluent-emoji.py <path of a clone of microsoft/fluentui-emoji>
Layout stays the same as the Noto sheets (8 columns, 72 px cells), so the app code does not change.
Anything that cannot be found keeps its Noto picture; any failure leaves the Noto sheets untouched (the build never breaks)."""
import json, os, re, sys, glob
try:
    from PIL import Image
except Exception as e:
    print('Pillow missing, keeping Noto emoji:', e); sys.exit(0)

root = os.path.join(sys.argv[1], 'assets')
here = os.path.dirname(os.path.abspath(__file__))
proj = os.path.dirname(here)
src = open(os.path.join(proj, 'src/js/emoji-data.js'), encoding='utf8').read()
cats = json.loads(re.search(r'EMJ_CATS=(\[.*\]);', src, re.S).group(1))
COLS, CELL = 8, 72

# glyph (without the variation selector) -> folder of the 3D picture
idx = {}
for meta in glob.glob(os.path.join(root, '*', 'metadata.json')):
    try:
        g = json.load(open(meta, encoding='utf8')).get('glyph', '')
    except Exception:
        continue
    g = g.replace('️', '')
    if len(g) == 1 and g not in idx:
        idx[g] = os.path.dirname(meta)

def pic(folder):
    # items with skin tones keep their pictures in <folder>/Default/3D/, the others in <folder>/3D/
    for pat in ('3D/*.png', 'Default/3D/*.png', '*/3D/*.png'):
        f = sorted(glob.glob(os.path.join(folder, pat)))
        if f:
            return f[0]
    return None

total = hit = 0
for key, cover, cps in cats:
    path = os.path.join(proj, 'www/img/em', key + '.webp')
    try:
        sheet = Image.open(path).convert('RGBA')
    except Exception:
        sheet = Image.new('RGBA', (COLS * CELL, ((len(cps) + COLS - 1) // COLS) * CELL), (0, 0, 0, 0))
    for i, cp in enumerate(cps):
        total += 1
        fol = idx.get(chr(cp))
        f = pic(fol) if fol else None
        if not f:
            continue
        try:
            im = Image.open(f).convert('RGBA')
            bb = im.getbbox()
            if bb:
                im = im.crop(bb)
            side = max(im.size)
            sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
            sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
            sq = sq.resize((CELL - 6, CELL - 6), Image.LANCZOS)
            x, y = (i % COLS) * CELL, (i // COLS) * CELL
            sheet.paste((0, 0, 0, 0), (x, y, x + CELL, y + CELL))
            sheet.alpha_composite(sq, (x + 3, y + 3))
            hit += 1
        except Exception as e:
            print('skip', cp, e)
    sheet.save(path, 'WEBP', quality=85, method=6)
print('Fluent emoji: %d of %d replaced (the rest keep Noto)' % (hit, total))
