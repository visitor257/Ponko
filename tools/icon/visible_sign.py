# -*- coding: utf-8 -*-
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
"""在图片上画**可见**署名（区别于 watermark.py 的不可见水印）。

## 为什么还要可见署名

`tools/watermark.py` 嵌的是像素级不可见水印（DCT 中频 QIM），目的是"图被解包出去
之后还能证明归属"。它的问题是**必须先用工具验证才能证明**——普通用户在应用商店、
在论坛上看到这张图，看不出任何归属信息。可见署名补的是这一层：一眼就能看到。

两者不冲突，各管一件事：
  · 不可见水印 —— 给"要主张权利"的场合用（发现盗用、向平台投诉）
  · 可见署名   —— 给"日常可见"的场合用（截图、转发、商店页）

## 为什么署名放底部居中，而不是角落

Android 自适应图标只显示 108dp 画布中间 72dp 的圆，**四角会被遮罩裁掉**。
署名放角落的话，在圆形遮罩的启动器上直接消失——等于没加。底部居中的位置
在任何遮罩形状（圆、圆角方、方）下都保留。

## 为什么白字 + 深描边

图标底是白的，主体是立绘（浅色皮肤/浅色衣服居多），单用深色字会融进背景，
单用白字在浅色区域又会看不见。白字配一圈深色描边，两种背景上都有边界。

## 为什么小图只写 ©

48px 的 mdpi 图标上，`© Ponko` 六个字符每个只有 4~5px 宽，渲染出来是一团糊，
比不写更难看。这种情况只画 `©` —— 一个字符，至少能辨认出是个圈，
表达"有归属"的意思就够了。超过 72px 才写字。
"""
import os

from PIL import Image, ImageDraw, ImageFont

# 字体按优先级找。都用系统自带的，不额外分发字体文件（授权干净）。
_FONT_CANDIDATES = [
    r"C:\Windows\Fonts\segoeui.ttf",     # Windows 默认 UI 字体，© 字形规整
    r"C:\Windows\Fonts\arial.ttf",
    r"C:\Windows\Fonts\tahoma.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]

# 只有小图（不写字）时用这个
TEXT_TINY = "©"
# 正常尺寸用这个。不含真实姓名，只用已在仓库 URL 里公开的标识
TEXT_ICON = "© Ponko"
TEXT_LARGE = "© Ponko · visitor257"

# 超过这个宽度（像素）才写字，否则只画 ©
MIN_WIDTH_FOR_TEXT = 72

# 只画 © 的小图可以放得更大：一个字符不用横向铺开，宽度不是约束。
# 48px 图标上 0.085 只能算出 4px 的 ©，看上去像脏点；放大 1.6 倍才认得出来。
TINY_SCALE = 1.6

# 描边宽度 / 字号。见 _draw_sign 里的说明
STROKE_RATIO = 0.07

# 自适应图标的可见区（72/108）。传统图标是 1.0
ADAPTIVE_VISIBLE = 72.0 / 108.0


def to_canvas(ratio, visible=1.0):
    """把**显示区坐标**换算成**画布坐标**。

    ## 为什么需要这个换算（这一版才加，之前是 bug 来源）

    同一句 "右下角 90%"，在两种图上意思完全不同：

    | 图层 | 显示区占画布 | 画布的 90% 是显示区的 |
    |---|---|---|
    | 传统图标 ic_launcher.png | 全部 | 90% |
    | 自适应前景层 foreground | 中间 72/108（两侧各留 18/108） | **126%**（跑到画外） |

    所以位置**必须按显示区说**，不能按画布说。第一版直接给画布比例、两层用同一组数字，
    结果两层都跑到显示区 60~65% 的位置 —— 用户看到的仍然是"在中间"。

    @param ratio    显示区里的相对位置，0 = 左/上边缘，1 = 右/下边缘
    @param visible  显示区占画布的比例：传统图标 1.0，自适应前景层 [ADAPTIVE_VISIBLE]
    """
    return (1.0 - visible) / 2.0 + visible * ratio


def display_room(visible=ADAPTIVE_VISIBLE):
    """显示区右下角**能放到的**显示区坐标上限。

    ## 两种图标的上限其实是同一个数

    · **自适应前景层**：只显示中间 72/108 的圆，画布坐标上
      `(x1-n/2)² + (y1-n/2)² ≤ (n/3)²` → 45° 上限 0.7356n
    · **传统图标**：整图显示，但启动器普遍会套一个**内切圆**遮罩，
      此时半径 n/2、45° 上限 `0.5n + 0.3536n = 0.8536n`

    换算回各自的显示区坐标，两个都是 **0.8535**。所以参数可以统一，
    不需要为两种图标维护两套位置 —— 这是实测出来的巧合，不是设计出来的。

    实现取 **0.80** 而不是 0.8535：余量留给描边和整数取整。
    实测 0.83 时 48px 那档的角正好压在圆上（浮点差一点点就判超），
    0.80 让最小档也有 1px 以上的安全边 —— 半截字比多留 3% 边距难看太多。
    """
    return 0.80


def _load_font(px):
    """按像素高度取字体。找不到任何 ttf 时退到 PIL 内置位图字体。"""
    for p in _FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, max(1, int(round(px))))
            except Exception:
                continue
    return ImageFont.load_default()


def _measure(draw, text, font):
    """返回文字的实际墨迹尺寸 (宽, 高)。

    ⚠ 不能直接用 `textbbox` 的宽高当"绘制范围"：它给的是字体度量盒，
    而 `draw.text((x, y), ...)` 的 (x, y) 是**基线原点**，两者差着 ascent/descent。
    照度量盒算位置、照原点去画，字就会偏出去一截（实测偏了 23px，
    署名跑到预期区域之外）。这里改成真的画一遍、量墨迹。
    """
    probe = Image.new("L", (max(8, int(font.size * len(text) * 2)), int(font.size * 4)), 0)
    ImageDraw.Draw(probe).text((0, 0), text, font=font, fill=255)
    box = probe.getbbox()
    if box is None:
        return 0, 0
    return box[2] - box[0], box[3] - box[1]


def _font_px(n, height_ratio, tiny):
    return max(1, n * height_ratio * (TINY_SCALE if tiny else 1.0))


def sign_visible(
    im,
    text=None,
    *,
    height_ratio=0.050,
    right_ratio=None,
    bottom_ratio=None,
    visible=1.0,
    fill=(255, 255, 255, 235),
    stroke=(26, 28, 34, 215),
):
    """在图片**右下角**画署名，返回新图（不修改入参）。

    ## 位置按显示区说，不按画布

    [right_ratio] / [bottom_ratio] 是**显示区**里的相对位置（0=左/上，1=右/下），
    内部用 [to_canvas] 换算成画布坐标。`visible` 传显示区占画布的比例。

    自适应图标传 `visible=ADAPTIVE_VISIBLE`，传统图标留默认 1.0。
    不传 [right_ratio] 时自动取 [display_room]，也就是显示区右下角的物理上限
    （自适应 0.8535，传统 1.0）。

    @param im             RGBA 图片（方形）
    @param text           覆盖署名文字；None = 按尺寸自动选
    @param height_ratio   字号占**画布**高度的比例
    @param right_ratio    显示区里的右边界位置
    @param bottom_ratio   显示区里的底边位置
    @param visible        显示区占画布的比例
    @param fill           文字颜色
    @param stroke         描边颜色
    """
    im = im.convert("RGBA")
    n = im.size[0]
    if im.size[1] != n:
        raise ValueError("署名只支持方形画布，当前 %dx%d" % im.size)

    if right_ratio is None:
        right_ratio = display_room(visible)
    if bottom_ratio is None:
        bottom_ratio = display_room(visible)

    out = im.copy()
    draw = ImageDraw.Draw(out)
    _draw_sign(draw, n, text, height_ratio, right_ratio, bottom_ratio, visible,
               fill, stroke)
    return out


def _draw_sign(draw, n, text, height_ratio, right_ratio, bottom_ratio,
               visible, fill, stroke):
    """实际绘制。sign_visible 和 sign_region 共用，保证"量的"就是"画的"。"""
    tiny = n <= MIN_WIDTH_FOR_TEXT
    if text is None:
        text = TEXT_TINY if tiny else TEXT_ICON

    font_px = _font_px(n, height_ratio, tiny)
    font = _load_font(font_px)

    # 显示区坐标 → 画布坐标。这两个数就是第一版出错的地方（见 to_canvas）。
    x = n * to_canvas(right_ratio, visible)
    y = n * to_canvas(bottom_ratio, visible)

    # anchor="rb" = 右对齐 + 底边。用 anchor 而不是自己减宽高：
    # 字体度量里 ascent/descent 各字体不同，手算必偏（第一版偏了 23px）。
    #
    # 描边按**字号**算，不按画布 —— 按画布算的话 1024px 图上会得到 9px 描边，
    # 而字才 46px 高，描边比笔画还粗。
    stroke_w = max(1, int(round(font_px * STROKE_RATIO)))

    draw.text(
        (x, y), text, font=font, anchor="rb",
        fill=fill, stroke_width=stroke_w, stroke_fill=stroke,
    )


def sign_region(n, text=None, *, height_ratio=0.050, right_ratio=None,
                bottom_ratio=None, visible=1.0):
    """返回署名会占用的矩形 (x0, y0, x1, y1)，供校验用。

    校验脚本拿它确认"署名确实落在可见圆内 / 确实在右下角"，
    和 [sign_visible] 走完全同一套绘制逻辑，保证量出来的就是画出来的。
    """
    if right_ratio is None:
        right_ratio = display_room(visible)
    if bottom_ratio is None:
        bottom_ratio = display_room(visible)
    probe = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    draw = ImageDraw.Draw(probe)
    _draw_sign(draw, n, text, height_ratio, right_ratio, bottom_ratio, visible,
               (255, 255, 255, 255), (0, 0, 0, 255))
    box = probe.getbbox()
    if box is None:
        cx = n * to_canvas(right_ratio, visible)
        cy = n * to_canvas(bottom_ratio, visible)
        return (cx, cy, cx, cy)
    return box


def center_in_display(rect, n, visible=1.0):
    """署名墨迹中心在**显示区**里的相对位置。用来确认"看起来真的在右下角"。

    ⚠ 只看"右边界有没有过 0.5"是不够的 —— 文字本身有宽度，右边界到 0.70 时
    中心可能还在 0.60，视觉上就是居中。第一版就是这么错的。
    """
    x0, y0, x1, y1 = rect
    cx, cy = (x0 + x1) / 2.0 / n, (y0 + y1) / 2.0 / n
    if visible >= 0.999:
        return cx, cy
    off = (1.0 - visible) / 2.0
    return (cx - off) / visible, (cy - off) / visible


def inside_visible_circle(n, rect, visible=1.0):
    """署名的四个角是否都落在可见圆内。

    **圆半径 = 显示区 / 2 × n**，一个公式覆盖两种图标：

    · 传统图标 `visible=1.0` → 半径 0.5n（启动器套的是内切圆）
    · 自适应前景层 `visible=72/108` → 半径 n/3

    超出会被启动器裁掉，而且裁一半比完全不可见更难看（会变成半截字）。
    """
    x0, y0, x1, y1 = rect
    cx = cy = n / 2.0
    r = n * visible / 2.0
    worst = 0.0
    for px, py in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        d = ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5
        worst = max(worst, d)
    return worst <= r, worst, r
