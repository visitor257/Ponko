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

# 描边宽度 / 字号。见 sign_visible 里的说明
STROKE_RATIO = 0.07


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
    height_ratio=0.070,
    bottom_ratio=0.76,
    fill=(255, 255, 255, 235),
    stroke=(26, 28, 34, 215),
):
    """在图片底部居中画署名，返回新图（不修改入参）。

    @param im             RGBA 图片（方形，图标场景下是画布）
    @param text           覆盖署名文字；None = 按尺寸自动选
    @param height_ratio   字号占画布高度的比例
    @param bottom_ratio   文字**底边**距画布顶部的比例。

                         这个值不能随便定：可见圆最低点是 0.833（5/6），但**文字两端的
                         角**比中心离圆心更远，所以先撞线的是角，不是底边中点。
                         设文字宽 0.30n、居中，则底边 y 必须满足
                             (0.15n)² + (y-0.5n)² ≤ (n/3)²
                         解得 y ≤ 0.798n。取 0.78 留一点余量。
                         （一开始写 0.88，算下来是负数 —— 那时署名整个在圆外，
                          在圆形启动器上会被裁成半截字。见 sign_region 的自检。）
    @param fill           文字颜色
    @param stroke         描边颜色
    """
    im = im.convert("RGBA")
    n = im.size[0]
    if im.size[1] != n:
        raise ValueError("署名只支持方形画布，当前 %dx%d" % im.size)

    tiny = n <= MIN_WIDTH_FOR_TEXT
    if text is None:
        text = TEXT_TINY if tiny else TEXT_ICON

    out = im.copy()
    draw = ImageDraw.Draw(out)
    font_px = _font_px(n, height_ratio, tiny)
    font = _load_font(font_px)
    tw, th = _measure(draw, text, font)

    # anchor="mb" = middle-bottom：文字**底边中点**锚在给定点。
    # 用 anchor 而不是自己算 (x, y)：字体度量里 ascent/descent 的分布各字体不同，
    # 自己减容易偏；anchor 由 Pillow 按字体自己算。
    #
    # 描边按**字号**比例算，不按画布 —— 按画布算的话，1024px 的图上会得到 9px 描边，
    # 而字才 46px 高，描边比笔画还粗。
    #
    # 0.07 是在"白色背景"和"深色立绘"两头都能用的值：
    # 图标底是纯白，白字只能靠描边显出轮廓；立绘底部实测平均亮度 79（偏深），
    # 白字本来就看得到，描边只需处理暗部里的亮点。
    stroke_w = max(1, int(round(font_px * STROKE_RATIO)))

    draw.text(
        (n / 2.0, n * bottom_ratio), text, font=font, anchor="mb",
        fill=fill, stroke_width=stroke_w, stroke_fill=stroke,
    )
    return out


def sign_region(n, text=None, *, height_ratio=0.070, bottom_ratio=0.76):
    """返回署名会占用的矩形 (x0, y0, x1, y1)，供校验用。

    校验脚本拿它确认"署名确实落在可见圆内"，而不是凭肉眼看。
    和 [sign_visible] 走同一套 anchor 逻辑，保证量出来的就是画出来的。
    """
    tiny = n <= MIN_WIDTH_FOR_TEXT
    if text is None:
        text = TEXT_TINY if tiny else TEXT_ICON
    font_px = _font_px(n, height_ratio, tiny)
    font = _load_font(font_px)
    probe = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    draw = ImageDraw.Draw(probe)
    stroke_w = max(1, int(round(font_px * STROKE_RATIO)))
    draw.text(
        (n / 2.0, n * bottom_ratio), text, font=font, anchor="mb",
        fill=(255, 255, 255, 255), stroke_width=stroke_w,
        stroke_fill=(0, 0, 0, 255),
    )
    box = probe.getbbox()
    if box is None:
        return (n / 2.0, n * bottom_ratio, n / 2.0, n * bottom_ratio)
    return box


def inside_visible_circle(n, rect, visible=72.0 / 108.0):
    """署名的四个角是否都落在可见圆内。

    自适应图标只显示中间 72/108 的圆；超出部分的署名会被启动器裁掉，
    而且裁一半比完全不可见更难看（会变成半截字）。
    """
    x0, y0, x1, y1 = rect
    cx = cy = n / 2.0
    r = n * visible / 2.0
    worst = 0.0
    for px, py in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        d = ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5
        worst = max(worst, d)
    return worst <= r, worst, r
