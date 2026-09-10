# -*- coding: utf-8 -*-
"""原图等比缩小、完整放进图标画布（不裁切、不抠底、不换背景、不变形）

系统启动器会按自己的形状（圆形/圆角/方形）裁切图标边缘，108dp 画布里真正可见的
只有中间 72dp。要让原图在任何形状下都完整可见，内容的最大外接圆必须 ≤ 72dp，
即整体缩到约 47.1%（72/108/√2 ≈ 0.471）。四周用原图自带的白色底延伸填满。

ICON_FILL 可覆盖比例（默认 0.471 = 零裁切；0.50 只丢 0.6%；0.611 等于官方 66dp 安全区）。

产出：
  mipmap-*/ic_launcher.png / ic_launcher_round.png   传统图标（48dp 基准）
  mipmap-*/ic_launcher_background.png                自适应背景层（108dp 纯白）
  mipmap-*/ic_launcher_foreground.png                自适应前景层（108dp，原图居中缩小）
  mipmap-anydpi-v26/ic_launcher(.round).xml
"""
import os

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SRC = os.environ.get("ICON_SRC") or os.path.join(HERE, "source.png")
RES = os.path.join(REPO, "app", "src", "main", "res")
FILL = float(os.environ.get("ICON_FILL", "0.471"))
BG = (255, 255, 255, 255)
VISIBLE = 72.0 / 108.0  # 系统可见区 / 画布

DENS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

img = Image.open(SRC).convert("RGBA")
w, h = img.size
if w != h:
    s = min(w, h)
    img = img.crop(((w - s) // 2, (h - s) // 2, (w + s) // 2, (h + s) // 2))


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    print("  %-56s %dx%d" % (os.path.relpath(path, REPO), im.size[0], im.size[1]))


def compose(size, fill, bg=BG):
    c = Image.new("RGBA", (size, size), bg)
    side = max(1, int(round(size * fill)))
    im = img.resize((side, side), Image.LANCZOS)
    o = (size - side) // 2
    c.paste(im, (o, o), im)
    return c


def compose_fg(size, fill):
    c = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    side = max(1, int(round(size * fill)))
    im = img.resize((side, side), Image.LANCZOS)
    o = (size - side) // 2
    c.paste(im, (o, o), im)
    return c


def circle(im):
    m = Image.new("L", im.size, 0)
    r = im.size[0] * VISIBLE
    o = (im.size[0] - r) / 2.0
    ImageDraw.Draw(m).ellipse((o, o, o + r - 1, o + r - 1), fill=255)
    out = im.copy()
    out.putalpha(m)
    return out


def lost_ratio(im, tol=30):
    a = np.asarray(im.convert("RGB")).astype(int)
    nonwhite = np.abs(a - 255).sum(axis=2) > tol
    n = im.size[0]
    yy, xx = np.mgrid[0:n, 0:n]
    dist = np.sqrt((xx - n / 2 + 0.5) ** 2 + (yy - n / 2 + 0.5) ** 2)
    outside = dist > (n * VISIBLE) / 2
    lost = nonwhite & outside
    return lost.sum(), max(1, nonwhite.sum()), lost, nonwhite


for d, px in DENS:
    full = compose(px, FILL)
    save(full, os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
    save(full, os.path.join(RES, "mipmap-" + d, "ic_launcher_round.png"))
    big = int(round(px * 108 / 48.0))
    save(Image.new("RGBA", (big, big), BG),
         os.path.join(RES, "mipmap-" + d, "ic_launcher_background.png"))
    save(compose_fg(big, FILL),
         os.path.join(RES, "mipmap-" + d, "ic_launcher_foreground.png"))

old = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
if os.path.exists(old):
    os.remove(old)
    print("  rm res/drawable/ic_launcher_foreground.xml")
for d, _ in DENS:
    dp = os.path.join(RES, "drawable-" + d)
    if os.path.isdir(dp) and not os.listdir(dp):
        os.rmdir(dp)
if os.path.isdir(os.path.join(RES, "drawable")) and not os.listdir(os.path.join(RES, "drawable")):
    os.rmdir(os.path.join(RES, "drawable"))

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    p = os.path.join(RES, "mipmap-anydpi-v26", name)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(ADAPTIVE)
    print("  write res/mipmap-anydpi-v26/" + name)

# ---- 预览：旧 61% 圆形(标红被切部分) / 新比例圆形 / 新比例 48px ----
S = 432
old61 = compose(S, 0.611)
lost_n, total, lost, nonwhite = lost_ratio(old61)
print("  61%% 比例在圆形下被切 %d/%d 像素 (%.2f%%)" % (lost_n, total, 100.0 * lost_n / total))
vis = np.asarray(old61.convert("RGB")).copy()
vis[lost] = [255, 40, 40]
old_marked = Image.fromarray(vis)

new_c = compose(S, FILL)
ln, tt, _, _ = lost_ratio(new_c)
print("  %.3f 比例在圆形下被切 %d/%d 像素 (%.2f%%)" % (FILL, ln, tt, 100.0 * ln / tt))

out = os.path.join(HERE, "out", "preview")
os.makedirs(out, exist_ok=True)
items = [circle(old_marked), circle(new_c),
         new_c.resize((48, 48), Image.LANCZOS).resize((S, S), Image.NEAREST)]
sheet = Image.new("RGBA", (S * 3 + 80, S + 40), (22, 24, 32, 255))
for i, im2 in enumerate(items):
    sheet.paste(im2, (20 + i * (S + 20), 20), im2)
sheet.convert("RGB").save(os.path.join(out, "fit-compare.png"))
print("  write tools/icon/out/preview/fit-compare.png")
print("done  (fill=%.3f)" % FILL)
