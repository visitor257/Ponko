# -*- coding: utf-8 -*-
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
"""手动给**网页图标**盖署名 —— 鼠标点到哪，署名就印在哪。

## 为什么单独一个工具

`stamp_gui.py` 管的是 `app/src/main/res/` 下的十几张图标，而且会先重跑
`make-icon-plain.py`、从备份恢复立绘、补不可见水印 —— 那一整套对网页图标
既不需要（网页只用一个尺寸）也不该发生（会连带重写 App 的资源）。

网页图标只有一个目标：`docs/icon.png`。

## 为什么不自动定位

位置我调过两次都不对：先是右下角 0.8535（那是**自适应图标的圆内上限**，
网页根本没有圆），再是同一套参数照搬 —— 看起来"在角上"，实际离边太近或太远。
**这种"看着顺不顺眼"的事代码验不出来，只能眼睛看。**

所以这个脚本只做两件事：给你一张干净底图，然后把你点的位置精确印上去。

## 用法

    python tools/icon/stamp-web.py            # 打开窗口，点位置
    python tools/icon/stamp-web.py --clean    # 只重置成干净底图（不打开窗口）

窗口里的操作：

| 操作 | 效果 |
|---|---|
| **左键点击** | 以点击点为字心盖章，存盘后关闭 |
| `+` / `-` | 字号变大 / 变小（实时预览） |
| `[` / `]` | 描边变细 / 变粗 |
| `T` | 轮换文案：全名 → `© Ponko` → `Ponko` → `©` |
| `C` | 显示/隐藏**建议边距**框 |
| `Esc` | 不盖章退出 |

## 和 App 图标的区别

| 项 | App 图标 | 网页图标（本工具） |
|---|---|---|
| 遮罩 | 启动器内切圆，圆外会被裁 | **无遮罩**，整张都显示 |
| 安全区 | 自适应只显示中间 72/108 | 无强制要求，但贴边会显得挤 |
| 建议位置 | 圆内 45° 上限 = 0.8535 | 留 5% 边距，不压人物主体 |
| 文案默认 | `© Ponko`（自动摆放要避让）| **`© Ponko · visitor257`**（方形无避让压力，写全）|

## 印完

`docs/icon.png` 已经是最终交付物（含量化压缩）。要上线：

    cd LiteRT-Chat && tools/push-via-api.py

Pages 会在 20~60 秒内重建。**推送前记得核对 Pages 构建的 commit 是刚推的那个 sha**，
否则会看到旧图（踩过：拿旧文件判定成"没生效"）。
"""
import argparse
import os
import sys

import tkinter as tk
from tkinter import messagebox

from PIL import Image, ImageDraw, ImageTk

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import visible_sign  # noqa: E402

# ---- 目标 ----
SOURCE = os.path.join(HERE, "source.png")          # 1920px 原稿
OUT = os.path.join(REPO, "docs", "icon.png")       # 网页用
OUT_SIZE = 256                                     # 显示 30px 时 8x 超采样

# 原稿里内容占 87.5%（make-icon-plain.py 会再补成 75% 的安全区）。
# 网页要内容铺满，所以从原稿裁掉那 12.5% 的边。
CROP_INSET = 0.125
CROP_BOX = (int(1920 * CROP_INSET), int(1920 * CROP_INSET),
            int(1920 * (1 - CROP_INSET)), int(1920 * (1 - CROP_INSET)))

# 窗口显示尺寸
VIEW = 560

# 建议边距（占画布比例）。超出这个框不算错，只是提醒"这可能显得贴边"。
SAFE_MARGIN = 0.05

# 文案候选，第一项是默认。`T` 循环。
#
# 默认写全名而不是 `© Ponko`：`© Ponko` 只说明"Ponko 出品"，但 Ponko 是
# **应用名**不是作者 —— 图被单独转到别处时，看不出是谁做的。
# `visitor257` 已在仓库 URL 里公开，不扩大披露范围。
#
# 这与 App 图标的默认（`© Ponko`）不同，是刻意的：那里的自动摆放要同时
# 满足"在圆内"和"不压主体"，18 个字符在 192px 上等于半个图标在写字；
# 网页是方形、无遮罩、256px，写全名不挤。
TEXTS = [
    "© Ponko · visitor257",
    "© Ponko",
    "Ponko",
    "©",
]

# 字号占画布比例。
#
# **比 App 图标的 0.038 大**，是刻意的。0.038 是为了在 App 图标里同时避开
# 启动器圆形遮罩和人物主体 —— 约束多，字只能小。网页图标是方形、无遮罩，
# 唯一限制是别太抢主体。
#
# 但也不能太大：0.055 时全名墨迹 126×12（占画布 49%），**右下角放不下**
# ——字心最右只能到 x=189，再往右就越界（实测溢出 43px）。
# 0.045 → 12px 字、墨迹 109×11（43%），字心可到 x=198，
# 右下角能完整放下，点开原图也看得清。这是权衡后的值，不是随手写的。
#
# 嫌大嫌小按 `+`/`-`；窗口里的虚线框是建议边距，不是必须对齐的边界。
HEIGHT_RATIO = 0.045


def clean_base():
    """原稿 → 无署名的干净底图（RGBA，未压缩）。"""
    if not os.path.exists(SOURCE):
        raise SystemExit("找不到原稿：%s" % SOURCE)
    im = Image.open(SOURCE).convert("RGB")
    if im.size[0] != im.size[1]:
        raise SystemExit("原稿必须是方形，当前 %dx%d" % im.size)
    n = im.size[0]
    box = tuple(int(n * r) for r in (CROP_INSET, CROP_INSET,
                                     1 - CROP_INSET, 1 - CROP_INSET))
    return im.crop(box).resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS).convert("RGBA")


def save_final(im, path):
    """量化到 256 色再存。

    照片类 PNG 未量化在 256px 下约 100 KB；quantize(256) 后约 42 KB，
    实测平均色差 1.98/255 —— 这个尺寸下肉眼看不出，而页面本身才 31 KB，
    一张 100 KB 的图标不合比例。
    """
    q = im.convert("RGB").quantize(colors=256, method=Image.MEDIANCUT,
                                   dither=Image.FLOYDSTEINBERG)
    q.save(path, "PNG", optimize=True)
    return os.path.getsize(path)


class StampWeb:
    def __init__(self, root, at=None):
        self.root = root
        self.at = at           # 非交互模式：直接印在这个坐标（原图像素）
        self.ratio = HEIGHT_RATIO
        self.stroke_ratio = visible_sign.STROKE_RATIO
        self.text_idx = 0
        self.show_safe = True
        self._ink = {}
        self._tiles = {}
        self.preview_item = None
        self.preview_photo = None

        self.orig = clean_base()
        self.n = self.orig.size[0]
        self.zoom = VIEW / float(self.n)
        self.offset = 0        # OUT_SIZE 缩放后正好铺满 VIEW，无偏移

        root.title("Ponko · 网页图标署名")
        root.configure(bg="#1a1c22")
        root.resizable(False, False)

        self.canvas = tk.Canvas(root, width=VIEW, height=VIEW, bg="#1a1c22",
                                highlightthickness=0, cursor="crosshair")
        self.canvas.pack(padx=12, pady=(12, 4))
        self.canvas.bind("<Button-1>", self.on_click)
        self.canvas.bind("<Motion>", self.on_move)
        self.canvas.bind("<Leave>", lambda e: self.hide_preview())

        self.status = tk.Label(root, text="", bg="#1a1c22", fg="#c8ccd6",
                               font=("Microsoft YaHei UI", 10), justify="left",
                               anchor="w", padx=12, pady=8, width=64)
        self.status.pack(fill="x")

        root.bind("+", lambda e: self.bump(1.12))
        root.bind("=", lambda e: self.bump(1.12))
        root.bind("-", lambda e: self.bump(1 / 1.12))
        root.bind("bracketright", lambda e: self.bump_stroke(1.25))
        root.bind("bracketleft", lambda e: self.bump_stroke(1 / 1.25))
        root.bind("t", lambda e: self.cycle_text())
        root.bind("T", lambda e: self.cycle_text())
        root.bind("c", lambda e: self.toggle_safe())
        root.bind("C", lambda e: self.toggle_safe())
        root.bind("<Escape>", lambda e: self.quit_no_save())

        self.redraw()
        self.update_status()

        if at is not None:
            # 非交互：跳过事件循环，直接印
            self.stamp(at[0], at[1])
            root.after(60, root.destroy)

    # ---------------- 绘制 ----------------

    def view_of(self, x, y):
        return int(round(x * self.zoom)), int(round(y * self.zoom))

    def to_image_space(self, ev):
        x = (ev.x - self.offset) / self.zoom
        y = (ev.y - self.offset) / self.zoom
        if 0 <= x <= self.n and 0 <= y <= self.n:
            return x, y
        return None

    def redraw(self):
        shown = self.orig.convert("RGB").resize((VIEW, VIEW), Image.LANCZOS)
        self.photo = ImageTk.PhotoImage(shown)
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, anchor="nw", image=self.photo)

        if self.show_safe:
            # 建议边距：不阻止，只是给个"别贴边"的参照。
            # 用虚线而不是实线，免得看着像必须对齐的框。
            m = SAFE_MARGIN * VIEW
            self.canvas.create_rectangle(m, m, VIEW - m, VIEW - m,
                                         outline="#4C7DFF", width=2, dash=(6, 6))

    def update_status(self, extra=""):
        txt = ("左键点击 = 在点击处盖章（点击点即文字正中）\n"
               "文案：%s   字号：%.3f 画布  描边：%.3f\n"
               "＋/－ 字号   [ / ] 描边   T 换文案   C 边距框   Esc 退出"
               % (self.context_text(), self.ratio, self.stroke_ratio))
        if extra:
            txt = extra + "\n" + txt
        self.status.config(text=txt)

    def context_text(self):
        return TEXTS[self.text_idx]

    def font_px(self):
        return max(1, int(round(self.n * self.ratio)))

    def ink_size(self, text):
        fpx = self.font_px()
        key = (text, fpx)
        if key not in self._ink:
            font = visible_sign._load_font(fpx)
            w, h = visible_sign._measure(ImageDraw.Draw(Image.new("L", (4, 4))),
                                         text, font)
            sw = max(1, int(round(fpx * self.stroke_ratio)))
            self._ink[key] = (w + 2 * sw, h + 2 * sw)
        return self._ink[key]

    def overflow(self, x, y):
        """以 (x,y) 为字心印下去，超出画布多少。0 = 完整可见。"""
        w, h = self.ink_size(self.context_text())
        rect = (x - w / 2.0, y - h / 2.0, x + w / 2.0, y + h / 2.0)
        return max(0.0, -rect[0], -rect[1], rect[2] - self.n, rect[3] - self.n)

    # ---------------- 预览 ----------------

    def preview_tile(self, text, bad):
        fpx = self.font_px()
        key = (text, fpx, bad)
        if key in self._tiles:
            return self._tiles[key]
        pad = max(2, int(fpx * self.stroke_ratio) + 2)
        font = visible_sign._load_font(fpx)
        sw = max(1, int(round(fpx * self.stroke_ratio)))
        d_probe = ImageDraw.Draw(Image.new("L", (4, 4)))
        tw, th = visible_sign._measure(d_probe, text, font)
        tile = Image.new("RGBA", (tw + 2 * pad + 2 * sw, th + 2 * pad + 2 * sw),
                         (0, 0, 0, 0))
        draw = ImageDraw.Draw(tile)
        draw.text((tile.size[0] / 2, tile.size[1] / 2), text, font=font,
                  anchor="mm", fill=(255, 255, 255, 240), stroke_width=sw,
                  stroke_fill=(220, 60, 60, 255) if bad else (18, 20, 26, 228))
        big = tile.resize((tile.size[0] * VIEW // self.n * 1,
                           tile.size[1] * VIEW // self.n * 1), Image.LANCZOS)
        photo = ImageTk.PhotoImage(big)
        self._tiles[key] = photo
        return photo

    def on_move(self, ev):
        pt = self.to_image_space(ev)
        if pt is None:
            self.hide_preview()
            return
        bad = self.overflow(*pt) > 0
        tile = self.preview_tile(self.context_text(), bad)
        self.hide_preview()
        self.preview_photo = tile
        self.preview_item = self.canvas.create_image(
            ev.x, ev.y, anchor="center", image=tile)

    def hide_preview(self):
        if self.preview_item is not None:
            self.canvas.delete(self.preview_item)
            self.preview_item = None

    # ---------------- 盖章 ----------------

    def stamp(self, x, y):
        font_px = self.font_px()
        font = visible_sign._load_font(font_px)
        sw = max(1, int(round(font_px * self.stroke_ratio)))
        draw = ImageDraw.Draw(self.orig)
        # anchor="mm" = 点击点是小字正中。这样"我点哪印哪"最符合直觉。
        draw.text((x, y), self.context_text(), font=font, anchor="mm",
                  fill=(255, 255, 255, 240), stroke_width=sw,
                  stroke_fill=(18, 20, 26, 228))
        size = save_final(self.orig, OUT)
        print("已盖章 -> %s (%d B)" % (OUT, size))
        print("文案：%s" % self.context_text())
        print("字心：(%.1f, %.1f) / %d  字号 %.1fpx  描边 %dpx"
              % (x, y, self.n, font_px, sw))
        print("右/下边距：%.1f / %.1f px" % (self.n - x, self.n - y))
        return size

    def on_click(self, ev):
        pt = self.to_image_space(ev)
        if pt is None:
            return
        over = self.overflow(*pt)
        if over > 0:
            self.status.config(text="⚠ 有 %.0fpx 超出画布（会被切掉），请往内点" % over)
            return
        self.stamp(*pt)
        self.root.after(120, self.root.destroy)

    # ---------------- 参数 ----------------

    def bump(self, k):
        self.ratio = max(0.012, min(0.20, self.ratio * k))
        self._ink.clear(); self._tiles.clear()
        self.redraw(); self.update_status()

    def bump_stroke(self, k):
        self.stroke_ratio = max(0.02, min(0.25, self.stroke_ratio * k))
        self._ink.clear(); self._tiles.clear()
        self.redraw(); self.update_status()

    def cycle_text(self):
        self.text_idx = (self.text_idx + 1) % len(TEXTS)
        self._ink.clear(); self._tiles.clear()
        self.redraw(); self.update_status()

    def toggle_safe(self):
        self.show_safe = not self.show_safe
        self.redraw(); self.update_status()

    def quit_no_save(self):
        print("已取消，未修改 %s" % OUT)
        self.root.destroy()


def main():
    ap = argparse.ArgumentParser(description="给网页图标 docs/icon.png 手动盖署名")
    ap.add_argument("--clean", action="store_true",
                    help="只重置成干净底图（无署名），不打开窗口")
    ap.add_argument("--at", metavar="X,Y",
                    help="非交互：直接以 (X,Y)（256 画布坐标）为字心盖章")
    ap.add_argument("--text", metavar="S", help="指定文案（默认第一项 = 全名）")
    ap.add_argument("--ratio", type=float, default=HEIGHT_RATIO, help="字号占画布比例")
    args = ap.parse_args()

    if args.clean:
        im = clean_base()
        size = save_final(im, OUT)
        print("已重置为干净底图（无署名）-> %s (%d B)" % (OUT, size))
        return

    if args.text:
        if args.text not in TEXTS:
            TEXTS.insert(0, args.text)
        else:
            TEXTS.insert(0, TEXTS.pop(TEXTS.index(args.text)))
        HEIGHT_RATIO_LOCAL = args.ratio
    else:
        HEIGHT_RATIO_LOCAL = args.ratio

    at = None
    if args.at:
        try:
            ax, ay = [float(v) for v in args.at.split(",")]
        except ValueError:
            raise SystemExit("--at 格式应为 X,Y，例如 --at 200,220")
        at = (ax, ay)

    root = tk.Tk()
    app = StampWeb(root, at=at)
    app.ratio = HEIGHT_RATIO_LOCAL
    if not at:
        root.mainloop()


if __name__ == "__main__":
    main()
