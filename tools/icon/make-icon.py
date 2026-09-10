#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ponko 启动图标生成器
====================
输入：一张方形立绘（白底）
输出：完整的 Android 图标资源树 + 预览图

实现的规范：
  * 自适应图标画布 108dp，关键内容限制在中间 66dp「安全圆」内（任何遮罩都不会裁到）
  * 前景层 = 透明 PNG（只有角色）
  * 背景层 = 深蓝径向渐变
  * 单色层 = 纯白剪影（Android 13+ 主题图标）
  * 传统图标 = 圆角方形 + 圆形，取可见区 72dp 裁切
"""
import os
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.environ.get("ICON_SRC") or os.path.join(HERE, "source.png")
OUT = os.environ.get("ICON_OUT") or os.path.join(HERE, "out")

SAFE = 66.0 / 108.0        # 安全区直径占画布比例
VISIBLE = 72.0 / 108.0     # 可见区直径占画布比例
FILL = 0.60                # 内容较长边占画布比例（越小越不易被圆形遮罩切到）
ADAPTIVE_DP = 108
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
LEGACY_PX = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
MARK = (255, 0, 255)
WORK_SIZE = 1600           # 处理分辨率（最终图标最大 432px，够用）


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------- 抠底
def cutout_white(img, thresh=20):
    """从四边 flood fill 掉连通的白背景，返回 (RGBA, 透明度占比)"""
    rgb = img.convert("RGB")
    W, H = rgb.size
    work = rgb.copy()
    seeds = [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1),
             (W // 2, 0), (W // 2, H - 1), (0, H // 2), (W - 1, H // 2),
             (W // 4, 0), (W // 4, H - 1), (3 * W // 4, 0), (3 * W // 4, H - 1)]
    for s in seeds:
        px = work.getpixel(s)
        if all(abs(c - m) <= 2 for c, m in zip(px, MARK)):
            continue
        ImageDraw.floodfill(work, s, MARK, thresh=thresh)

    arr = np.asarray(work).astype(np.int16)
    marked = np.all(np.abs(arr - np.array(MARK, dtype=np.int16)) <= 2, axis=-1)
    alpha = np.where(marked, 0, 255).astype(np.uint8)

    # 1) 腐蚀掉最外圈（白边所在）
    a = Image.fromarray(alpha, "L").filter(ImageFilter.MinFilter(5))
    # 2) 羽化
    a = a.filter(ImageFilter.GaussianBlur(0.9))
    af = np.asarray(a).astype(np.float32) / 255.0

    # 3) 去白边：边缘半透明像素按「与白底混合」反解回原始色
    rgb_f = np.asarray(rgb).astype(np.float32)
    a3 = af[..., None]
    decon = np.clip((rgb_f - (1.0 - a3) * 255.0) / np.maximum(a3, 0.10), 0, 255)
    soft = (af > 0.04) & (af < 0.96)
    out_rgb = np.where(soft[..., None], decon, rgb_f).astype(np.uint8)

    rgba = Image.fromarray(np.dstack([out_rgb, (af * 255.0).astype(np.uint8)]), "RGBA")
    return rgba, float((af > 0.06).mean())


# ---------------------------------------------------------------- 度量与前景
def art_metrics(rgba, q=99.7):
    a = np.asarray(rgba.getchannel("A"))
    ys, xs = np.where(a > 16)
    if len(xs) == 0:
        raise RuntimeError("抠图后没有任何内容 —— 背景可能不是纯白")
    cx, cy = (xs.min() + xs.max()) / 2.0, (ys.min() + ys.max()) / 2.0
    d = np.hypot(xs - cx, ys - cy)
    return (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())), (cx, cy), float(np.percentile(d, q))


def build_foreground(rgba, canvas, fill=VISIBLE):
    """内容较长边缩放到可见区直径，按包围盒居中"""
    (x0, y0, x1, y1), (cx, cy), r = art_metrics(rgba)
    pad = 6
    x0 = max(0, x0 - pad); y0 = max(0, y0 - pad)
    x1 = min(rgba.width - 1, x1 + pad); y1 = min(rgba.height - 1, y1 + pad)
    art = rgba.crop((x0, y0, x1 + 1, y1 + 1))

    s = (canvas * fill) / float(max(art.width, art.height))
    nw, nh = max(1, round(art.width * s)), max(1, round(art.height * s))
    art = art.resize((nw, nh), Image.LANCZOS)

    ox = (canvas - nw) / 2.0
    oy = (canvas - nh) / 2.0
    big = Image.new("RGBA", (canvas * 3, canvas * 3), (0, 0, 0, 0))
    big.alpha_composite(art, (int(round(canvas + ox)), int(round(canvas + oy))))
    fg = big.crop((canvas, canvas, canvas * 2, canvas * 2))
    return fg, dict(bbox=(x0, y0, x1, y1), scale=s, size=(nw, nh), cover=nw / float(canvas))


# ---------------------------------------------------------------- 边缘渐隐
# 原图是裁切很满的立绘，四边都是“硬切口”。在图标里直接缩小会看到平直切边，
# 所以对内容包围盒的四边做 alpha 渐隐，让角色自然融入背景。
def edge_fade_content(fg, top=0.05, bottom=0.13, side=0.09):
    a = np.asarray(fg.getchannel("A")).astype(np.float32)
    ys, xs = np.where(a > 8)
    if len(xs) == 0:
        return fg
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    W, H = x1 - x0 + 1, y1 - y0 + 1
    ramp = np.ones_like(a)

    def lin(n):
        return np.linspace(0.0, 1.0, n, dtype=np.float32)

    nt = max(2, int(H * top));    ramp[y0:y0 + nt] *= lin(nt)[:, None]
    nb = max(2, int(H * bottom)); ramp[y1 - nb + 1:y1 + 1] *= lin(nb)[::-1][:, None]
    ns = max(2, int(W * side));   ramp[:, x0:x0 + ns] *= lin(ns)[None, :]
    ramp[:, x1 - ns + 1:x1 + 1] *= lin(ns)[::-1][None, :]

    out = fg.copy()
    out.putalpha(Image.fromarray(np.clip(a * ramp, 0, 255).astype(np.uint8), "L"))
    return out


def rim_fade(fg, radius=VISIBLE / 2.0, feather=0.08):
    """圆形遮罩边缘软化：只影响最外缘一小圈，让超出圆的内容淡出而非硬切"""
    n = fg.width
    c = (n - 1) / 2.0
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    dist = np.hypot(xx - c, yy - c)
    R = n * radius
    ramp = np.clip((R - dist) / (R * feather), 0.0, 1.0)
    a = np.asarray(fg.getchannel("A")).astype(np.float32)
    out = fg.copy()
    out.putalpha(Image.fromarray(np.clip(a * ramp, 0, 255).astype(np.uint8), "L"))
    return out


# ---------------------------------------------------------------- 背景 / 单色
def build_background(canvas):
    y, x = np.mgrid[0:canvas, 0:canvas].astype(np.float32)
    c = (canvas - 1) / 2.0
    d = np.clip(np.hypot(x - c, y - c) / (canvas / 2.0), 0, 1) ** 1.25
    inner = np.array([44, 64, 105], dtype=np.float32)   # #2C4069
    outer = np.array([23, 34, 58], dtype=np.float32)    # #17223A
    col = inner[None, None, :] * (1 - d[..., None]) + outer[None, None, :] * d[..., None]
    return Image.fromarray(col.astype(np.uint8), "RGB")


def build_monochrome(fg):
    out = Image.new("RGBA", fg.size, (255, 255, 255, 255))
    out.putalpha(fg.getchannel("A"))
    return out


# ---------------------------------------------------------------- 传统图标
def round_mask(px, ratio=0.22):
    m = Image.new("L", (px, px), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, px - 1, px - 1], radius=max(1, int(px * ratio)), fill=255)
    return m


def circle_mask(px):
    m = Image.new("L", (px, px), 0)
    ImageDraw.Draw(m).ellipse([0, 0, px - 1, px - 1], fill=255)
    return m


def visible_crop(img108):
    """取 108dp 画布中间的 72dp 可见区"""
    n = img108.width
    a = (n - int(round(n * VISIBLE))) // 2
    b = a + int(round(n * VISIBLE))
    return img108.crop((a, a, b, b))


# ---------------------------------------------------------------- 主流程
def main():
    os.makedirs(OUT, exist_ok=True)
    res = os.path.join(OUT, "res")
    preview = os.path.join(OUT, "preview")
    for d in [res, preview]:
        os.makedirs(d, exist_ok=True)

    log(f"[1/5] 读取原图 {SRC}")
    src = Image.open(SRC).convert("RGB")
    log(f"      原图 {src.width}x{src.height}")
    if src.width != src.height:
        log("      非正方形，先按短边居中裁切")
        n = min(src.width, src.height)
        src = src.crop(((src.width - n) // 2, (src.height - n) // 2,
                        (src.width + n) // 2, (src.height + n) // 2))
    if src.width > WORK_SIZE:
        src = src.resize((WORK_SIZE, WORK_SIZE), Image.LANCZOS)
        log(f"      缩到 {WORK_SIZE}x{WORK_SIZE} 处理")

    log("[2/5] 抠白底（flood fill，可能需要几十秒）...")
    rgba, ink = cutout_white(src)
    log(f"      保留内容占比 {ink*100:.1f}%")
    rgba.save(os.path.join(preview, "cutout.png"))

    log("[3/5] 布局到 108dp 画布（内容限制在 66/108 安全区）")
    N = ADAPTIVE_DP * 4          # 以 432px 为母版
    fg, meta = build_foreground(rgba, N, FILL)
    log(f"      bbox={meta['bbox']} 缩放={meta['scale']:.3f} 尺寸={meta['size']} 占画布宽={meta['cover']*100:.0f}%")
    fg = edge_fade_content(fg)
    fg = rim_fade(fg)
    log("      四边已渐隐（顶 5% / 底 13% / 左右 9%）+ 圆形边缘软化")
    bg = build_background(N)
    mono = build_monochrome(fg)

    log("[4/5] 导出 Android 资源")
    for name, k in DENSITIES:
        px = int(round(ADAPTIVE_DP * k))
        dd = os.path.join(res, f"drawable-{name}")
        os.makedirs(dd, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(dd, "ic_launcher_foreground.png"))
        bg.resize((px, px), Image.LANCZOS).save(os.path.join(dd, "ic_launcher_background.png"))
        mono.resize((px, px), Image.LANCZOS).save(os.path.join(dd, "ic_launcher_monochrome.png"))

    # 合成 108dp 母版（先前景后背景压在一起，用于传统图标与预览）
    composed = Image.alpha_composite(bg.convert("RGBA"), fg)
    composed.save(os.path.join(preview, "full-108dp.png"))

    for name, px in LEGACY_PX:
        vis = visible_crop(composed)
        base = vis.resize((px, px), Image.LANCZOS)
        os.makedirs(os.path.join(res, f"mipmap-{name}"), exist_ok=True)
        sq = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        sq.paste(base, (0, 0), round_mask(px))
        sq.save(os.path.join(res, f"mipmap-{name}", "ic_launcher.png"))
        rd = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rd.paste(base, (0, 0), circle_mask(px))
        rd.save(os.path.join(res, f"mipmap-{name}", "ic_launcher_round.png"))

    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@drawable/ic_launcher_background" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
           '</adaptive-icon>\n')
    ad = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(ad, exist_ok=True)
    open(os.path.join(ad, "ic_launcher.xml"), "w", encoding="utf-8").write(xml)
    open(os.path.join(ad, "ic_launcher_round.xml"), "w", encoding="utf-8").write(xml)

    log("[5/5] 生成预览")
    P = 384
    comp = composed.resize((P, P), Image.LANCZOS)
    for tag, mask in (("round", circle_mask(P)), ("square", round_mask(P))):
        im = Image.new("RGBA", (P, P), (0, 0, 0, 0))
        im.paste(comp, (0, 0), mask)
        im.save(os.path.join(preview, f"preview-{tag}.png"))

    # 三种壁纸背景 + 48px 实拍
    for tag, col in (("ondark", (18, 20, 26, 255)), ("onlight", (238, 240, 245, 255))):
        canvas = Image.new("RGBA", (P + 120, P + 120), col)
        im = Image.new("RGBA", (P, P), (0, 0, 0, 0))
        im.paste(comp, (0, 0), circle_mask(P))
        canvas.alpha_composite(im, (60, 60))
        canvas.convert("RGB").save(os.path.join(preview, f"preview-{tag}.png"))

    small = composed.resize((48, 48), Image.LANCZOS)
    small.save(os.path.join(preview, "real-48.png"))
    small.resize((192, 192), Image.NEAREST).save(os.path.join(preview, "preview-48-zoom4x.png"))

    log("完成 → " + OUT)


if __name__ == "__main__":
    main()
