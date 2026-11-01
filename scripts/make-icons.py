#!/usr/bin/env python3
"""Builds the Android launcher icons from icons/source/ghost.jpg.
Only the box and what is inside it is kept; the purple backdrop of the source picture becomes transparent.
Needs: pillow, numpy, scipy, scikit-image."""
import os
from PIL import Image, ImageFilter
import numpy as np
from scipy import ndimage as ndi
from skimage.morphology import convex_hull_image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "icons", "source", "ghost.jpg")
OUT = os.path.join(ROOT, "icons")
RIM = 9.8          # width (source px) of the dark bezel around the lighter "screen"

im = Image.open(SRC).convert("RGB")
a = np.asarray(im).astype(int)
S = (a[..., 1] > 58) & (a[..., 2] > 225)           # the lighter inner screen
S = ndi.binary_opening(S, iterations=1)
lab, n = ndi.label(S)
S = lab == (1 + int(np.argmax(ndi.sum(S, lab, range(1, n + 1)))))
S = ndi.binary_fill_holes(S)
dist = ndi.distance_transform_edt(~convex_hull_image(S)).astype(np.float32)   # distance to the screen shape
ys, xs = np.where(dist <= RIM + 1)
x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
side = max(x1 - x0, y1 - y0)
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
x0, y0 = int(round(cx - side / 2)), int(round(cy - side / 2))
rgb = im.crop((x0, y0, x0 + side, y0 + side))
dst = Image.fromarray(dist[y0:y0 + side, x0:x0 + side], mode="F")

def box(px):
    """RGBA picture of the box, px wide and tall, transparent outside the box."""
    k = px / side
    c = rgb.resize((px, px), Image.LANCZOS)
    if k > 1:
        c = c.filter(ImageFilter.UnsharpMask(radius=1.2 * k, percent=60, threshold=2))
    d = np.asarray(dst.resize((px, px), Image.BICUBIC))
    alpha = np.clip((RIM - d) * k + 0.5, 0, 1)
    out = c.convert("RGBA")
    out.putalpha(Image.fromarray((alpha * 255).astype(np.uint8)))
    return out

def canvas(size, frac):
    """Transparent size x size canvas with the box centred, taking frac of the width."""
    px = max(1, int(round(size * frac)))
    c = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c.alpha_composite(box(px), ((size - px) // 2, (size - px) // 2))
    return c

canvas(1024, 1.0).save(os.path.join(OUT, "icon-1024.png"))
canvas(512, 1.0).save(os.path.join(OUT, "icon-512.png"))
LEG = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for d, s in LEG.items():
    p = os.path.join(OUT, "mipmap-" + d)
    os.makedirs(p, exist_ok=True)
    canvas(s, 0.96).save(os.path.join(p, "ic_launcher.png"))
    canvas(s, 0.96).save(os.path.join(p, "ic_launcher_round.png"))
    fg = int(round(s * 108 / 48))
    canvas(fg, 0.82).save(os.path.join(p, "ic_launcher_foreground.png"))
print("icons written, source box", side, "px")
