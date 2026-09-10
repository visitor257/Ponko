# -*- coding: utf-8 -*-
"""原图直出图标：对原图零改动（不抠底、不加背景、不渐隐、不缩构图），
各密度直接缩放，自适应图标的背景层就用原图本身。

产出：
  mipmap-*/ic_launcher.png / ic_launcher_round.png   传统图标（48dp 基准）
  mipmap-*/ic_launcher_background.png                自适应图标背景层（108dp 铺满）
  drawable/ic_launcher_foreground.xml                自适应图标前景层（透明，不动原图）
  mipmap-anydpi-v26/ic_launcher(.round).xml
"""
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SRC = os.environ.get("ICON_SRC") or os.path.join(HERE, "source.png")
RES = os.path.join(REPO, "app", "src", "main", "res")

DENS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
SCALE_108 = 108 / 48.0

img = Image.open(SRC).convert("RGBA")
w, h = img.size
if w != h:
    s = min(w, h)
    img = img.crop(((w - s) // 2, (h - s) // 2, (w + s) // 2, (h + s) // 2))


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    print("  %-58s %dx%d" % (os.path.relpath(path, REPO), im.size[0], im.size[1]))


for d, px in DENS:
    im = img.resize((px, px), Image.LANCZOS)
    save(im, os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
    save(im, os.path.join(RES, "mipmap-" + d, "ic_launcher_round.png"))
    bpx = int(round(px * SCALE_108))
    save(img.resize((bpx, bpx), Image.LANCZOS),
         os.path.join(RES, "mipmap-" + d, "ic_launcher_background.png"))
    for f in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"):
        p = os.path.join(RES, "drawable-" + d, f)
        if os.path.exists(p):
            os.remove(p)
            print("  rm", os.path.relpath(p, REPO))
    dp = os.path.join(RES, "drawable-" + d)
    if os.path.isdir(dp) and not os.listdir(dp):
        os.rmdir(dp)

FG = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#00000000" />
</shape>
"""
save_txt = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
os.makedirs(os.path.dirname(save_txt), exist_ok=True)
with open(save_txt, "w", encoding="utf-8") as fh:
    fh.write(FG)
print("  write res/drawable/ic_launcher_foreground.xml")

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    p = os.path.join(RES, "mipmap-anydpi-v26", name)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(ADAPTIVE)
    print("  write res/mipmap-anydpi-v26/" + name)

# 效果预览（系统圆形遮罩下的样子）
from PIL import ImageDraw
S = 432
prev = img.resize((S, S), Image.LANCZOS)
m = Image.new("L", (S, S), 0)
ImageDraw.Draw(m).ellipse((0, 0, S - 1, S - 1), fill=255)
rnd = prev.copy()
rnd.putalpha(m)
out = os.path.join(HERE, "out", "preview")
os.makedirs(out, exist_ok=True)
rnd.convert("RGB").save(os.path.join(out, "plain-circle.png"))
rnd.resize((48, 48), Image.LANCZOS).resize((192, 192), Image.NEAREST).convert("RGB").save(
    os.path.join(out, "plain-48-zoom4x.png"))
print("  write tools/icon/out/preview/plain-circle.png, plain-48-zoom4x.png")
print("done")
