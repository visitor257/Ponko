# -*- coding: utf-8 -*-
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
"""手动给图标盖署名 —— 鼠标点到哪，署名就印在哪。

## 为什么要有这个工具

位置参数我调了三次都没对：先是居中，再是"右对齐但中心还在 0.60"，
最后才发现图标可见区是内切圆、文字宽了放不进角。
**这种"看起来在不在角上"的事，代码里验不出来，只能眼睛看。**
与其我再猜第四次，不如把这件事交回给能看屏幕的人。

## 用法

    python tools/icon/stamp_gui.py

会自动做两件事再打开窗口：

1. `SIGN=0` 重跑 `make-icon-plain.py`，得到**没有署名**的干净底图
   （不这么做的话，手印上去会叠在旧署名上，字会变粗变糊）
2. 从 `_backup/about_portrait-original.png` 恢复立绘，并补上不可见水印
   （立绘的水印不可逆，只能从原稿重做，不能叠）

然后逐张显示，你点哪里就盖在哪里。

## 窗口里的操作

| 操作 | 效果 |
|---|---|
| **左键点击** | 在该位置盖章，短暂显示结果后进入下一张 |
| `+` / `-` | 字号变大 / 变小（实时预览） |
| `[` / `]` | 描边变细 / 变粗 |
| `C` | 显示/隐藏**可见圆**（启动器会沿这个圆裁切，圆外的字会被切掉） |
| `Z` 或 Backspace | 撤销上一张，回到它重新点 |
| `S` | 跳过这一张（不盖章） |
| `Esc` | 保存已完成的、退出 |

## 显示比例

小图标会放大到约 520px 显示，鼠标旁的预览字**按同样的比例放大**，
所以看到多大，实际就是多大。

## 盖的是什么字

默认**按图自动选**：> 72px 用 `© Ponko · visitor257`，≤ 72px 用 `©`
（18 个字符挤进 48~72 像素是一团糊）。按 `T` 可以固定成某一个文案。

字号默认占画布 0.038（图标）/ 0.024（立绘）—— 全名在 192px 上字宽约 33%，
是"看得出但不抢主体"的比例。嫌大嫌小按 `+` / `-`。

## 会不会被裁

鼠标旁的**预览字变红**＝这个位置放不下，启动器会裁掉一部分。
判据是**文字墨迹的四个角**都在可见圆内，不是只看点击的那一个点 ——
字有宽度，"中心在圆内但两头探出去"是最常见的半截字来源。

## 印完要做什么

工具只改 `app/src/main/res/` 下的 PNG。改完还得重编 APK：

    cd C:\\PonkoBuild && tools\\build-android.bat :app:assembleRelease

（`tools/icon/README.md` 里也写了。）
"""
import os
import shutil
import subprocess
import sys
import tempfile
import tkinter as tk

import numpy as np
from tkinter import messagebox

from PIL import Image, ImageDraw, ImageTk

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(REPO, "app", "src", "main", "res")

sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import visible_sign  # noqa: E402
import watermark as wm  # noqa: E402

# 显示目标尺寸。小图标放大到这个大小才看得清。
VIEW = 520
# 立绘原稿（带水印之前的那份）。立绘的水印不可逆，只能从它重做。
PORTRAIT_SRC = os.path.join(os.path.dirname(REPO), "_backup", "about_portrait-original.png")

# 可选的署名文案，按 `T` 循环切换（第一项是"自动"，按图的大小自己选）。
#
# 默认是**带作者名**的 `© Ponko · visitor257`：`© Ponko` 只说明"Ponko 出品"，
# 但 Ponko 是应用名不是作者，图被单独转出去时看不出是谁做的。
# `visitor257` 本来就在仓库 URL 里公开，不算扩大公开范围。
#
# 但**小图（≤72px）放不下全名**：18 个字符挤进 48~72 像素，每个字 1~2px，
# 渲染出来是一团糊，比不写更难看。那一档自动退回 `©`（一个字符至少认得出）。
TEXTS = [
    "© Ponko · visitor257",
    "© Ponko",
    "Ponko",
    "©",
]

# 字号占**画布高度**的比例。0.038 是实测选的：
#   全名在 192px 上字宽 ≈ 33% 画布（0.055 时是 52%，半个图标都在写字，太抢）
#   而且各档密度换算出来的相对大小一致 —— 传统图标和自适应前景层的
#   dp 换算不同，但按画布比例给字号，两者视觉上一样大。
HEIGHT_RATIO_ICON = 0.038
# 立绘 1024px：0.024 → 字宽 222px（21.7%），比之前 0.028 收了一档
HEIGHT_RATIO_PORTRAIT = 0.024

DENS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]


def inscribe_circle(im, ss=8):
    """裁成圆形内容：圆（内切于画布）外变透明，圆内不动。

    用于 `ic_launcher_round.png`。和 `make-icon-plain.py` 里的 `circle()`
    是同一件事，两边各留一份以免 GUI 启动时依赖那个脚本的执行副作用。

    ## 为什么用超采样画圆（`ss=8`）

    `ImageDraw.ellipse` **不抗锯齿**：直接画 96px 的圆，边缘是一圈硬台阶，
    在白色启动器背景上非常明显。先在 `ss` 倍画布上画、再用 LANCZOS 缩回来，
    边缘就是渐变的 alpha（实测半透明像素从 0 变成 1000+），肉眼平滑。
    """
    n = im.size[0]
    big = Image.new("L", (n * ss, n * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, n * ss - 1, n * ss - 1), fill=255)
    mask = big.resize((n, n), Image.LANCZOS)
    out = im.convert("RGBA").copy()
    out.putalpha(mask)
    return out


def build_jobs():
    """要处理的图，按从大到小排 —— 先在大图上定位置，小图跟着同一个比例走。"""
    jobs = []
    for d, px in reversed(DENS):
        big = int(round(px * 108 / 48.0))
        jobs.append({
            "path": os.path.join(RES, "mipmap-" + d, "ic_launcher.png"),
            "size": px,
            "text": TEXTS[0],
            # 传统图标整图显示，但启动器套内切圆 → 显示区 = 全图
            "visible": 1.0,
            "label": "%s / ic_launcher (%dpx)" % (d, px),
            "mask": "circle",     # 启动器套内切圆
            "copy_to": os.path.join(RES, "mipmap-" + d, "ic_launcher_round.png"),
        })
        jobs.append({
            "path": os.path.join(RES, "mipmap-" + d, "ic_launcher_foreground.png"),
            "size": big,
            "text": TEXTS[0],
            # 自适应前景层只显示中间 72/108
            "visible": visible_sign.ADAPTIVE_VISIBLE,
            "label": "%s / foreground (%dpx，自适应)" % (d, big),
            "mask": "circle",
            "copy_to": None,
        })
    jobs.append({
        "path": os.path.join(RES, "drawable-nodpi", "about_portrait.png"),
        "size": 1024,
        "text": visible_sign.TEXT_LARGE,
        "visible": 1.0,
        "label": "关于页立绘 (1024px)",
        # 立绘在关于页里**整张显示**，没有启动器遮罩 —— 角落是能用的。
        # 拿圆去判它，等于凭空给四角画了个"禁放区"。
        "mask": "none",
        "copy_to": None,
    })
    return jobs


def prepare_clean():
    """把资源恢复到"没有可见署名"的状态。

    `NO_PREPARE=1` 时跳过 —— 中途想改字号重开，不该把已经印好的清掉。
    """
    if os.environ.get("NO_PREPARE") == "1":
        print("== 跳过准备（NO_PREPARE=1），保留已有改动 ==")
        return

    print("== 准备干净底图 ==")
    env = dict(os.environ, SIGN="0")
    r = subprocess.run([sys.executable, "make-icon-plain.py"],
                       cwd=HERE, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-2000:])
        print(r.stderr[-2000:])
        raise SystemExit("生成底图失败")
    print("  图标：已重新生成为无署名版本")

    # 立绘：从原稿重做（水印不可逆，只能重嵌，不能叠）
    dst = os.path.join(RES, "drawable-nodpi", "about_portrait.png")
    if not os.path.exists(PORTRAIT_SRC):
        raise SystemExit("找不到立绘原稿：%s" % PORTRAIT_SRC)
    src = Image.open(PORTRAIT_SRC).convert("RGB")
    marked = wm.embed(np.asarray(src)).astype(np.uint8)
    Image.fromarray(marked).convert("RGB").save(dst, "PNG", optimize=True)
    t, a = wm.read_mark(dst)
    print("  立绘：已从原稿重做，水印 %r 一致率 %.0f%%" % (t, a * 100))
    if a <= 0.99:
        raise SystemExit("立绘水印嵌入失败，中止")


class StampApp:
    def __init__(self, root, jobs):
        self.root = root
        self.jobs = jobs
        self.idx = 0
        # 字号按画布比例存，这样"上一张定好的比例"能带到下一张。
        # 图标和立绘**分开记**：立绘 1024px 用图标的 0.038 会得到 39px 的字，
        # 直接变成横幅。两类图各记各的，`+`/`-` 只改当前这一类。
        self.ratios = {"icon": HEIGHT_RATIO_ICON, "portrait": HEIGHT_RATIO_PORTRAIT}
        self.stroke_ratio = visible_sign.STROKE_RATIO
        # None = 按图自动选（大图全名、小图 ©）；按 `T` 才固定成某一个
        self.text_override = None
        self.show_circle = True
        self.history = []          # [(idx, 备份路径)]
        self._ink = {}             # (文案, 字号) → 墨迹 (宽, 高)，鼠标移动时不能每次重画
        self.tmpdir = tempfile.mkdtemp(prefix="ponko_stamp_")
        self.preview_item = None
        self.preview_photo = None
        self._tiles = {}           # (文案, 字号, 是否放得下) → PhotoImage，避免每动一次重画

        root.title("Ponko 图标署名")
        root.configure(bg="#1a1c22")
        root.resizable(False, False)

        self.canvas = tk.Canvas(root, width=VIEW, height=VIEW,
                                bg="#1a1c22", highlightthickness=0,
                                cursor="crosshair")
        self.canvas.pack(side="top", padx=12, pady=(12, 4))
        self.canvas.bind("<Button-1>", self.on_click)
        self.canvas.bind("<Motion>", self.on_move)
        self.canvas.bind("<Leave>", lambda e: self.hide_preview())

        self.status = tk.Label(root, text="", bg="#1a1c22", fg="#c8ccd6",
                               font=("Microsoft YaHei UI", 10), justify="left",
                               anchor="w", padx=12, pady=8)
        self.status.pack(side="top", fill="x")

        root.bind("+", lambda e: self.bump(1.12))
        root.bind("=", lambda e: self.bump(1.12))
        root.bind("equal", lambda e: self.bump(1.12))
        root.bind("-", lambda e: self.bump(1 / 1.12))
        root.bind("bracketright", lambda e: self.bump_stroke(1.25))
        root.bind("bracketleft", lambda e: self.bump_stroke(1 / 1.25))
        root.bind("t", lambda e: self.cycle_text())
        root.bind("T", lambda e: self.cycle_text())
        root.bind("c", lambda e: self.toggle_circle())
        root.bind("C", lambda e: self.toggle_circle())
        root.bind("z", lambda e: self.undo())
        root.bind("Z", lambda e: self.undo())
        root.bind("<BackSpace>", lambda e: self.undo())
        root.bind("s", lambda e: self.skip())
        root.bind("S", lambda e: self.skip())
        root.bind("<Escape>", lambda e: self.finish())

        self.load()

    # ---------------- 载入一张 ----------------

    def load(self):
        if self.idx >= len(self.jobs):
            self.finish()
            return
        job = self.jobs[self.idx]
        self.orig = Image.open(job["path"]).convert("RGBA")
        self.n = self.orig.size[0]
        # ⚠ 不能 `max(1.0, ...)` —— 那会禁止缩小，1024px 的立绘在 520 的窗口里
        # 就只画出中间 520px，**四边各 252px 被挤到窗口外**：既看不见也点不到，
        # 用户以为点在右下角、实际离边缘还有 24%。小图该放大、大图该缩小，两头都要。
        self.zoom = VIEW / float(self.n)
        view_px = int(round(self.n * self.zoom))
        self.view_px = view_px
        self.offset = (VIEW - view_px) // 2
        # 换图会让 zoom 变，而预览图是"真实像素放大 zoom 倍"做的 → 必须重做
        self._tiles.clear()
        self.build_mask(job)
        self.redraw()
        self.update_status()

    def redraw(self):
        job = self.jobs[self.idx]
        shown = self.orig.resize((self.view_px, self.view_px), Image.LANCZOS)
        self.photo = ImageTk.PhotoImage(shown)
        self.canvas.delete("all")
        self.canvas.create_image(self.offset, self.offset, anchor="nw", image=self.photo)

        # 圆外的区域：启动器不会显示。用半透明暗红压一层，一眼看出"这里印了白印"。
        #
        # 为什么必须画这个而不是只画个圆：
        #   · ic_launcher 的可见圆是内切圆，**四角**在外面（白边有 48% 落在圆外）
        #   · foreground 只显示中间 72/108，**四周一整圈**都在外面（透明边全废）
        # 只画一条圆线的话，很容易把字压在圆线上、一半在里一半在外，
        # 得到的是一半被裁掉的字。
        if self.show_circle:
            self.canvas.create_image(self.offset, self.offset, anchor="nw",
                                     image=self.mask_photo)

        self.hide_preview()

    def build_mask(self, job):
        """生成「不可见区」遮罩图（View 尺寸）。"""
        n = self.view_px
        if job.get("mask") != "circle":
            # 立绘整张都显示（没有启动器遮罩），不画任何红区
            self.mask_photo = ImageTk.PhotoImage(
                Image.new("RGBA", (n, n), (0, 0, 0, 0)))
            return
        r = n * job["visible"] / 2.0
        yy, xx = np.mgrid[0:n, 0:n]
        c = n / 2.0
        outside = ((xx - c) ** 2 + (yy - c) ** 2) > r * r
        img = np.zeros((n, n, 4), dtype=np.uint8)
        img[outside] = [255, 60, 60, 70]     # 半透明红
        outline = np.zeros((n, n), bool)
        dist = np.sqrt((xx - c) ** 2 + (yy - c) ** 2)
        outline = np.abs(dist - r) < 1.2
        img[outline] = [255, 170, 60, 255]   # 圆边界：橙色实线
        self.mask_photo = ImageTk.PhotoImage(Image.fromarray(img, "RGBA"))

    # ---------------- 鼠标 ----------------

    def to_image_space(self, ev):
        """窗口坐标 → 原图坐标。返回 None 表示落在图外。"""
        x = (ev.x - self.offset) / self.zoom
        y = (ev.y - self.offset) / self.zoom
        if 0 <= x <= self.n and 0 <= y <= self.n:
            return x, y
        return None

    def cur_text(self):
        """当前这张要印什么字。

        没按过 `T` 就按图自动选：小图（≤72px）只能放 `©`，其余用全名。
        按过 `T` 就一律用选中的那个 —— 包括在小图上选全名（那是人的选择，
        预览会变红提醒放不下）。
        """
        if self.text_override is not None:
            return TEXTS[self.text_override]
        return self.jobs[self.idx]["text"]

    def ratio_key(self):
        """当前这张属于哪一类（图标 / 立绘），字号比例按类分开记。"""
        return "portrait" if self.jobs[self.idx]["size"] >= 512 else "icon"

    @property
    def height_ratio(self):
        return self.ratios[self.ratio_key()]

    def font_px(self):
        """当前这张的字号（像素）。

        **不再对小图额外放大**（原来 `≤72px` 会乘 `TINY_SCALE = 1.6`）：
        那会让 mdpi/hdpi 两档的相对字号变成画布的 6%，而 xhdpi 以上是 3.8%，
        同一套图标里"小图上的字反而更大"，看着就是"大小不对"。
        现在所有档都按同一个画布比例，视觉一致。
        """
        return max(1, self.jobs[self.idx]["size"] * self.height_ratio)

    def ink_size(self, text):
        """当前字号下这句文案的墨迹尺寸（含描边）。

        缓存起来：`<Motion>` 每动一下就问一次，重画一遍再量太浪费。
        描边是往两侧各长 stroke_w，所以要加 2 倍 —— 只量字本身的话
        贴着圆边的那一侧实际上已经被裁掉了。
        """
        fpx = max(1, int(round(self.font_px())))
        key = (text, fpx)
        if key not in self._ink:
            font = visible_sign._load_font(fpx)
            w, h = visible_sign._measure(ImageDraw.Draw(Image.new("L", (4, 4))),
                                         text, font)
            stroke_w = max(1, int(round(fpx * self.stroke_ratio)))
            self._ink[key] = (w + 2 * stroke_w, h + 2 * stroke_w)
        return self._ink[key]

    def fits(self, x, y):
        """以 (x, y) 为字心印下去，会不会被可见圆裁掉。

        返回 (是否完整, 超出多少像素)。判据是墨迹**四个角**都在可见区里 ——
        只判断字心的话，"字心在圆内、两头越过圆边"会被放过，
        而那正是最典型的结果：一个两头被切掉的半截署名。

        立绘（`mask = none`）没有圆遮罩，边界就是画布本身。
        """
        job = self.jobs[self.idx]
        w, h = self.ink_size(self.cur_text())
        rect = (x - w / 2.0, y - h / 2.0, x + w / 2.0, y + h / 2.0)
        if job.get("mask") != "circle":
            over = max(0.0, -rect[0], -rect[1], rect[2] - self.n, rect[3] - self.n)
            return over <= 0, over
        vv = job["visible"]
        ok, worst, rad = visible_sign.inside_visible_circle(self.n, rect, vv)
        return ok, max(0.0, worst - rad)

    def preview_tile(self, text, ok):
        """把署名**按真实像素**画一遍，再放大到窗口倍数，作为鼠标旁的预览。

        ## 为什么不能再用 Tk 画字（原来就是这里不对）

        Tk 的 `font=("Segoe UI", -px)` 里 `-px` 是**字号（em）**，而实际墨迹只占
        其中一部分；PIL 那边 4px 的字，墨迹是 4px，占满整个 em。
        两边比值不一样 → 同一个数字在窗口里和印出来**大小对不上**，
        小图上尤其离谱（窗口里还看得清，印出来是一团）。

        现在改成：用 PIL 以 `font_px()` + 同样的描边参数画出真实结果，
        再用 NEAREST 放大 `zoom` 倍。放大后能看见**像素块**，那就是这张图
        实际会变成的样子 —— 模糊、看不清，也如实反映。
        """
        fpx = max(1, int(round(self.font_px())))
        key = (text, fpx, ok)
        if key in self._tiles:
            return self._tiles[key]
        font = visible_sign._load_font(fpx)
        stroke_w = max(1, int(round(fpx * self.stroke_ratio)))
        w, h = self.ink_size(text)
        pad = 2
        tile = Image.new("RGBA", (int(w) + pad * 2, int(h) + pad * 2), (0, 0, 0, 0))
        fill = (255, 255, 255, 240) if ok else (255, 90, 90, 240)
        ImageDraw.Draw(tile).text(
            (tile.size[0] / 2.0, tile.size[1] / 2.0), text, font=font,
            anchor="mm", fill=fill, stroke_width=stroke_w,
            stroke_fill=(18, 20, 26, 228))
        z = max(1.0, self.zoom)     # 立绘是缩小的（zoom<1），但预览不能跟着缩没
        tile = tile.resize((max(1, int(round(tile.size[0] * z))),
                            max(1, int(round(tile.size[1] * z)))), Image.NEAREST)
        photo = ImageTk.PhotoImage(tile)
        self._tiles[key] = photo
        return photo

    def on_move(self, ev):
        """鼠标后面跟着的预览。放不下时变红 —— 点下去之前就该知道会被裁。"""
        pt = self.to_image_space(ev)
        if pt is None:
            self.hide_preview()
            return
        ok, _ = self.fits(*pt)
        photo = self.preview_tile(self.cur_text(), ok)
        if self.preview_item is None:
            self.preview_item = self.canvas.create_image(
                ev.x, ev.y, anchor="center", image=photo, tags="preview")
        else:
            self.canvas.itemconfig(self.preview_item, image=photo)
            self.canvas.coords(self.preview_item, ev.x, ev.y)
        self.preview_photo = photo      # 防止被 GC 掉（Tk 不持有引用）

    def hide_preview(self):
        self.canvas.delete("preview")
        self.preview_item = None

    # ---------------- 盖章 ----------------

    def save(self, im, path):
        """写回 PNG。**有透明区必须存 RGBA**。

        ⚠ 之前这里一律 `convert("RGB")` 存，把自适应前景层的透明边压成了
        纯黑（实测四角从 alpha=0 变成 (0,0,0,255)）—— 那张图在手机上是
        "黑方块 + 中间立绘"，等于把图标弄坏了。透明通道是这张图的主体，
        不是可以牺牲的体积。
        """
        if im.mode == "RGBA" and im.getchannel("A").getextrema()[0] < 255:
            im.save(path, "PNG", optimize=True)
        else:
            im.convert("RGB").save(path, "PNG", optimize=True)

    def on_click(self, ev):
        pt = self.to_image_space(ev)
        if pt is None:
            return
        job = self.jobs[self.idx]
        x, y = pt

        # 备份这一张，供撤销
        bak = os.path.join(self.tmpdir, "%03d_%s" % (self.idx, os.path.basename(job["path"])))
        shutil.copy2(job["path"], bak)
        self.history.append((self.idx, bak))

        font_px = self.font_px()
        draw = ImageDraw.Draw(self.orig)
        font = visible_sign._load_font(font_px)
        stroke_w = max(1, int(round(font_px * self.stroke_ratio)))
        # anchor="mm"：点击点 = 文字正中。这样"印在这里"最符合直觉。
        draw.text((x, y), self.cur_text(), font=font, anchor="mm",
                  fill=(255, 255, 255, 240), stroke_width=stroke_w,
                  stroke_fill=(18, 20, 26, 228))

        self.save(self.orig, job["path"])
        if job["copy_to"]:
            # `ic_launcher_round.png` 要的是**圆形内容**（圆外透明）：
            # 系统读 roundIcon 时不套遮罩、直接显示，给方图会显示成方块。
            # 所以这里不能 `copy2`，而是从方图裁一个内切圆。
            self.save(inscribe_circle(self.orig), job["copy_to"])

        # 落在不可见区就提醒一句 —— 不阻止（有些人可能有意为之），
        # 但必须让人知道"这个位置启动器不会显示"。
        ok, over = self.fits(x, y)
        outside = not ok

        # 显示结果，让用户确认一下再走
        self.redraw_done()
        if outside:
            self.status.config(text="⚠ 有 %.0fpx 超出可见区（启动器会裁掉）。"
                                   "（已照样盖上；Z 可撤销重印）" % over)
        self.root.after(420, self.next)

    def redraw_done(self):
        shown = Image.open(self.jobs[self.idx]["path"]).convert("RGBA")
        shown = shown.resize((self.view_px, self.view_px), Image.LANCZOS)
        self.photo = ImageTk.PhotoImage(shown)
        self.canvas.delete("all")
        self.canvas.create_image(self.offset, self.offset, anchor="nw", image=self.photo)
        if self.show_circle:
            self.canvas.create_image(self.offset, self.offset, anchor="nw",
                                     image=self.mask_photo)
        self.status.config(text="已盖章 · 进入下一张…")

    def next(self):
        self.idx += 1
        self.load()

    def skip(self):
        self.status.config(text="跳过 · 进入下一张…")
        self.next()

    # ---------------- 参数调整 ----------------

    def bump(self, k):
        key = self.ratio_key()
        self.ratios[key] *= k
        self._ink.clear()          # 字号变了，缓存的墨迹尺寸作废
        self._tiles.clear()        # 预览图的放大倍数也跟着字号走
        px = self.font_px()
        self.update_status("字号 ≈ %.1fpx（占画布 %.3f）" % (px, self.ratios[key]))

    def bump_stroke(self, k):
        self.stroke_ratio *= k
        self._ink.clear()          # 描边算进墨迹尺寸里
        self._tiles.clear()
        self.update_status("描边 = 字号的 %.0f%%" % (self.stroke_ratio * 100))

    def cycle_text(self):
        """自动 → 全名 → © Ponko → Ponko → © → 自动。"""
        n = len(TEXTS)
        if self.text_override is None:
            self.text_override = 0
        else:
            self.text_override += 1
            if self.text_override >= n:
                self.text_override = None
        self._ink.clear()          # 墨迹尺寸跟着文案走，缓存必须清
        self._tiles.clear()
        self.hide_preview()
        self.update_status("文案 → %r" % self.cur_text())

    def toggle_circle(self):
        self.show_circle = not self.show_circle
        self.redraw()
        self.update_status("可见圆：" + ("显示" if self.show_circle else "隐藏"))

    def undo(self):
        if not self.history:
            self.update_status("没有可撤销的")
            return
        idx, bak = self.history.pop()
        shutil.copy2(bak, self.jobs[idx]["path"])
        if self.jobs[idx]["copy_to"]:
            # 同样要重新裁圆，不能直接复制方图的备份
            self.save(inscribe_circle(Image.open(bak).convert("RGBA")),
                      self.jobs[idx]["copy_to"])
        self.idx = idx
        self.load()
        self.update_status("已撤销，回到这一张")

    # ---------------- 状态栏 ----------------

    def update_status(self, extra=""):
        job = self.jobs[self.idx]
        w, h = self.ink_size(self.cur_text())
        fpx = self.font_px()
        line1 = "[%d/%d] %s   文案 %r   字号≈%.1fpx（墨迹 %dx%d）   描边%.0f%%   字宽≈%.0f%%画布%s" % (
            self.idx + 1, len(self.jobs), job["label"], self.cur_text(),
            fpx, w, h, self.stroke_ratio * 100, 100.0 * w / float(self.n),
            ("   可见圆" if self.show_circle else ""))
        # 小档位说清楚：这不是 bug，是 48/72px 上写 18 个字符的物理下限
        if fpx < 4:
            line1 += "\n⚠ 这一档只有 %.1fpx 高（18 个字符均分），肉眼几乎不可辨 ——" \
                     "按 T 可换成 `©`" % fpx
        line2 = "左键盖章 · T 换文案 · +/− 字号 · [ ] 描边 · C 可见圆 · Z 撤销 · S 跳过 · Esc 退出"
        if extra:
            line2 = extra + "    |    " + line2
        self.status.config(text=line1 + "\n" + line2)
        # 标题带上文案：改了 TEXTS 之后，旧窗口（老代码、老默认值）和新窗口
        # 长得一模一样，"改了没生效"要查半天。标题第一眼就能分辨。
        self.root.title("Ponko 图标署名 — %s  [%d/%d]" % (
            self.cur_text(), self.idx + 1, len(self.jobs)))

    def finish(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)
        done = len(self.history)
        self.root.destroy()
        print()
        print("== 结束 ==")
        print("盖章 %d 张，处理到第 %d/%d 张" % (done, min(self.idx, len(self.jobs)), len(self.jobs)))
        print("下一步要重编 APK：")
        print("  cd C:\\PonkoBuild && tools\\build-android.bat :app:assembleRelease")


def main():
    prepare_clean()
    jobs = build_jobs()
    # `START=n` 从第 n 张开始（0 起）—— 局部修复时跳过前面已经盖好的
    start = int(os.environ.get("START", "0") or 0)
    missing = [j["path"] for j in jobs if not os.path.exists(j["path"])]
    if missing:
        for m in missing:
            print("缺文件：" + m)
        return 2
    root = tk.Tk()
    app = StampApp(root, jobs)
    if 0 < start < len(jobs):
        app.idx = start
        app.load()
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
