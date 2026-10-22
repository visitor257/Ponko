# -*- coding: utf-8 -*-
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
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

## 可见署名

除背景层外，每张图标**右下角**都有 `© Ponko`（小图只写 `©`，见 visible_sign.py）。
**背景层故意不加**：自适应图标是 background 垫底、foreground 盖在上面，
foreground 只在立绘处不透明 —— 把署名画在背景层，它会被立绘遮掉大半，
只在边缘露出残字，比不加更难看。同理署名必须画在**前景层**（最上层）才完整可见。

位置参数 `SIGN_RIGHT` / `SIGN_BOTTOM` = 0.70 而不是贴边，因为自适应图标只显示
中间 72/108 的圆：贴边（0.9+）会跑到圆外，在圆形启动器上被裁成半截字。
0.70 换算到显示区是 80% 处，视觉上就是右下角。
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import visible_sign  # noqa: E402  （同目录模块，要在 sys.path 调整之后导入）

REPO = os.path.dirname(os.path.dirname(HERE))
SRC = os.environ.get("ICON_SRC") or os.path.join(HERE, "source.png")
RES = os.path.join(REPO, "app", "src", "main", "res")
FILL = float(os.environ.get("ICON_FILL", "0.75"))
BG = (255, 255, 255, 255)
VISIBLE = 72.0 / 108.0  # 系统可见区 / 画布

DENS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

# 图标署名参数。
#
# · 文案固定 `©`（不是 `© Ponko`）。图标可见区是**内切圆**，右下角是一段弧，
#   只有单字符放得进去：`© Ponko` 宽约 0.21n，右边界顶到圆边（显示区 0.80）时
#   左边界回到 0.59n，**中心还在 0.70**，看起来依旧偏中。一个 `©` 宽约 0.06n，
#   中心能到 0.77~0.79，才是真的右下角。
# · 位置用显示区坐标（[visible_sign.to_canvas] 内部换算），传统图标和
#   自适应前景层的显示区大小不同，但换算后视觉位置一致。
# · 字号 0.055：单字符可以大一点（不会横向铺开），在 48px 上也有 3px 高。
SIGN_KW = dict(height_ratio=0.055)
SIGN_TEXT = visible_sign.TEXT_TINY

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
    # 署名在**最终尺寸**上画，不能先画再缩 —— 缩放会把字糊成一团。
    # 传统图标（方图 + 圆图）也各自画一遍，同理。
    #
    # 两个层的 visible 不同，但换算后视觉位置一致：
    #   传统图标整图显示 → visible=1.0（启动器套内切圆，半径 0.5n）
    #   自适应前景层只显示中间 72/108 → visible=0.667（半径 n/3）
    full = visible_sign.sign_visible(
        compose(px, FILL), SIGN_TEXT, visible=1.0, **SIGN_KW)
    save(full, os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
    save(full, os.path.join(RES, "mipmap-" + d, "ic_launcher_round.png"))
    big = int(round(px * 108 / 48.0))
    # 背景层**不署名**：它垫在前景下面，署名会被立绘遮出残字（见文件头注释）
    save(Image.new("RGBA", (big, big), BG),
         os.path.join(RES, "mipmap-" + d, "ic_launcher_background.png"))
    save(visible_sign.sign_visible(
             compose_fg(big, FILL), SIGN_TEXT,
             visible=visible_sign.ADAPTIVE_VISIBLE, **SIGN_KW),
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

# ---- 校验两项 ----
#
# ① 署名必须落在可见圆内，否则圆形启动器会把它裁成半截字
# ② 署名**看起来**必须在右下角 —— 用墨迹中心在显示区里的坐标判定。
#    第①项过了不代表第②项成立：第一版就在圆内、但中心在显示区 0.60，
#    用户看到的就是"在中间"。这条判定必须按中心来，不能只看右边界。
bad = []
MIN_CENTER = 0.72          # 显示区里，中心至少要到 72% 才算"右下角"
for d, px in DENS:
    for label, size, vis in (
        ("ic_launcher", px, 1.0),
        ("ic_launcher_foreground", int(round(px * 108 / 48.0)), visible_sign.ADAPTIVE_VISIBLE),
    ):
        rect = visible_sign.sign_region(size, SIGN_TEXT, height_ratio=SIGN_KW["height_ratio"],
                                       visible=vis)
        ok, worst, rad = visible_sign.inside_visible_circle(size, rect, vis)
        if not ok:
            bad.append("%s/%s 最远角 %.1f > 半径 %.1f（会被裁）" % (d, label, worst, rad))
        cx, cy = visible_sign.center_in_display(rect, size, vis)
        if cx < MIN_CENTER or cy < MIN_CENTER:
            bad.append("%s/%s 显示区中心 (%.2f, %.2f) 偏中（需 ≥ %.2f）"
                       % (d, label, cx, cy, MIN_CENTER))
if bad:
    print("  ✗ 署名自检失败：")
    for b in bad:
        print("     " + b)
    raise SystemExit(1)
print("  署名自检：%d 个尺寸，圆内且在右下角" % (len(DENS) * 2))

# ---- 预览：旧 61% 圆形(标红被切部分) / 新比例圆形 / 新比例 48px ----
S = 432
new_c = visible_sign.sign_visible(compose(S, FILL), SIGN_TEXT, visible=1.0, **SIGN_KW)
ln, tt, lost, nonwhite = lost_ratio(new_c)
print("  %.3f 比例在圆形下被切 %d/%d 像素 (%.2f%%)" % (FILL, ln, tt, 100.0 * ln / tt))
vis = np.asarray(new_c.convert("RGB")).copy()
vis[lost] = [255, 40, 40]
# 统一成 RGBA：下面 paste 用 im2 当蒙版，RGB 图没有 alpha 通道会被 Pillow
# 当成"蒙版格式不对"直接抛 ValueError（bad transparency mask）
marked = Image.fromarray(vis).convert("RGBA")

out = os.path.join(HERE, "out", "preview")
os.makedirs(out, exist_ok=True)
items = [marked, circle(new_c),
         new_c.resize((48, 48), Image.LANCZOS).resize((S, S), Image.NEAREST)]
sheet = Image.new("RGBA", (S * 3 + 80, S + 40), (22, 24, 32, 255))
for i, im2 in enumerate(items):
    sheet.paste(im2, (20 + i * (S + 20), 20), im2)
sheet.convert("RGB").save(os.path.join(out, "fit-compare.png"))
print("  write tools/icon/out/preview/fit-compare.png")
print("done  (fill=%.3f)" % FILL)
