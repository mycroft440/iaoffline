"""Vectorizes the "IA OFFLINE" logo image into the Android adaptive-icon layers and the branding SVG.

Usage (needs Pillow, numpy and potracer):
    python3 scripts/build_app_icon.py branding/logo-source.jpg out/
then copy out/app_launcher_foreground.xml and out/app_launcher_monochrome.xml to
app/src/main/res/drawable/ and out/logo.svg to branding/. The background is @color/app_icon_background.
"""
import sys
import numpy as np
from PIL import Image
import potrace

SRC = sys.argv[1]
OUT = sys.argv[2]

UP = 4            # upsampling before tracing, for sub-pixel edges
THRESH = 143      # midway between the background (B≈37) and the logo (B≈250)
TARGET_R = 32.0   # radius (dp) of the circle the whole logo fits in, inside the 33 dp safe zone
BG = "#051625"

img = Image.open(SRC).convert("RGB")
arr = np.asarray(img).astype(float)

REGIONS = {
    "glyph": (1095, 440, 1660, 925),   # brain, slash and Wi-Fi
    "text": (965, 995, 1790, 1115),    # IA OFFLINE
}


def trace(box):
    x0, y0, x1, y1 = box
    blue = Image.fromarray(arr[y0:y1, x0:x1, 2].astype(np.uint8), "L")
    big = blue.resize(((x1 - x0) * UP, (y1 - y0) * UP), Image.BICUBIC)
    mask = np.asarray(big) > THRESH
    paths = potrace.Bitmap(~mask).trace(turdsize=8 * UP * UP, alphamax=1.0, opticurve=True, opttolerance=0.2)
    pts = np.argwhere(mask)[:, ::-1] / UP + (x0, y0)
    return paths, (x0, y0), pts


def fit_gradient(box):
    x0, y0, x1, y1 = box
    sub = arr[y0:y1, x0:x1]
    ys, xs = np.nonzero(sub[:, :, 2] > 215)  # well inside the shapes, away from anti-aliasing
    X = np.c_[xs + x0, ys + y0, np.ones(len(xs))]
    coefs = [np.linalg.lstsq(X, sub[ys, xs, c], rcond=None)[0] for c in range(3)]
    g = coefs[1][:2] if np.hypot(*coefs[1][:2]) > 1e-3 else coefs[0][:2]
    d = g / np.hypot(*g)
    pts = np.c_[xs + x0, ys + y0]
    t = pts @ d
    c = pts.mean(0)
    tc = c @ d
    a = c + d * (t.min() - tc)
    b = c + d * (t.max() - tc)

    def color(p):
        v = [int(round(min(255, max(0, co[0] * p[0] + co[1] * p[1] + co[2])))) for co in coefs]
        return "#%02X%02X%02X" % tuple(v)

    return a, b, color(a), color(b)


traced = {k: trace(v) for k, v in REGIONS.items()}
allpts = np.vstack([t[2] for t in traced.values()])


def enclosing_circle(points, iters=4000):
    # Badoiu-Clarkson on the convex-ish extreme points.
    c = points.mean(0)
    for i in range(1, iters + 1):
        far = points[np.argmax(((points - c) ** 2).sum(1))]
        c = c + (far - c) / (i + 1)
    r = np.sqrt(((points - c) ** 2).sum(1)).max()
    return c, r


sample = allpts[:: max(1, len(allpts) // 60000)]
center, radius = enclosing_circle(sample)
scale = TARGET_R / radius


def tx(p):
    return ((p[0] - center[0]) * scale + 54.0, (p[1] - center[1]) * scale + 54.0)


def fmt(v):
    s = "%.2f" % v
    s = s.rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def pt(p, off):
    x, y = tx((p.x / UP + off[0], p.y / UP + off[1]))
    return fmt(x) + "," + fmt(y)


def path_data(paths, off):
    out = []
    for curve in paths:
        out.append("M" + pt(curve.start_point, off))
        for seg in curve.segments:
            if seg.is_corner:
                out.append("L" + pt(seg.c, off) + "L" + pt(seg.end_point, off))
            else:
                out.append("C" + pt(seg.c1, off) + " " + pt(seg.c2, off) + " " + pt(seg.end_point, off))
        out.append("Z")
    return "".join(out)


data = {k: path_data(v[0], v[1]) for k, v in traced.items()}
grads = {k: fit_gradient(REGIONS[k]) for k in REGIONS}
grads = {k: (tx(a), tx(b), ca, cb) for k, (a, b, ca, cb) in grads.items()}
print("center", center, "radius", radius, "scale", scale, file=sys.stderr)
print("gradients", grads, file=sys.stderr)
print("lengths", {k: len(v) for k, v in data.items()}, file=sys.stderr)


def android_gradient(g):
    (sx, sy), (ex, ey), ca, cb = g
    return f"""        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="{fmt(sx)}"
                android:startY="{fmt(sy)}"
                android:endX="{fmt(ex)}"
                android:endY="{fmt(ey)}">
                <item android:offset="0" android:color="#FF{ca[1:]}" />
                <item android:offset="1" android:color="#FF{cb[1:]}" />
            </gradient>
        </aapt:attr>"""


HEAD = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
"""

fg = HEAD.format(aapt='\n    xmlns:aapt="http://schemas.android.com/aapt"') + f"""    <!-- App logo "IA OFFLINE": a circuit brain crossed by a slash over Wi-Fi waves (AI with no
         internet), in a blue-to-cyan gradient, with the name below. Vectorized from branding/logo-source.jpg. -->
    <path
        android:fillType="evenOdd"
        android:pathData="{data['glyph']}">
{android_gradient(grads['glyph'])}
    </path>
    <path
        android:fillType="evenOdd"
        android:pathData="{data['text']}">
{android_gradient(grads['text'])}
    </path>
</vector>
"""

mono = HEAD.format(aapt="") + f"""    <!-- Themed icon (Android 13+): the same logo in a single color. -->
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{data['glyph']}" />
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{data['text']}" />
</vector>
"""

def svg_grad(id_, g):
    (sx, sy), (ex, ey), ca, cb = g
    return (f'<linearGradient id="{id_}" x1="{fmt(sx)}" y1="{fmt(sy)}" x2="{fmt(ex)}" y2="{fmt(ey)}" '
            f'gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{ca}"/><stop offset="1" stop-color="{cb}"/></linearGradient>')


svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
<defs>{svg_grad('g', grads['glyph'])}{svg_grad('t', grads['text'])}</defs>
<rect width="108" height="108" fill="{BG}"/>
<path fill-rule="evenodd" fill="url(#g)" d="{data['glyph']}"/>
<path fill-rule="evenodd" fill="url(#t)" d="{data['text']}"/>
</svg>
"""

import os
os.makedirs(OUT, exist_ok=True)
for name, content in [("app_launcher_foreground.xml", fg), ("app_launcher_monochrome.xml", mono),
                      ("logo.svg", svg)]:
    with open(os.path.join(OUT, name), "w") as f:
        f.write(content)
