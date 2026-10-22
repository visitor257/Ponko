# 图标生成

- `source.png` —— 图标原图（1920×1920，白底立绘）
- `make-icon.py` —— **抠底版**：抠掉白底、加深蓝渐变背景、边缘渐隐
- `make-icon-plain.py` —— **原样版**（当前在用）：不抠底、不换背景，原图等比缩小后居中
- `visible_sign.py` —— 可见署名模块，两个脚本共用

当前 `app/src/main/res` 里的图标由 `make-icon-plain.py` 生成。

## 用法

```bash
pip install pillow numpy
python make-icon-plain.py       # 直接写进 app/src/main/res/
python make-icon.py             # 输出到 out/res/，需手动覆盖
```

`make-icon-plain.py` 会**直接改写** `app/src/main/res/mipmap-*/` 下的文件，
不需要再手动复制。

## 可见署名（2026-10-22 起）

除背景层外，每张图标底部居中都有署名：

| 图标尺寸 | 署名内容 | 说明 |
|---|---|---|
| ≤ 72px | `©` | 只一个字符。这个尺寸上写全名每个字只有 3~4px 宽，渲染出来是一团糊 |
| > 72px | `© Ponko` | |

立绘 `app/src/main/res/drawable-nodpi/about_portrait.png` 用 `© Ponko · visitor257`，
见文件末尾的处理命令。

### 位置：右下角

**第一版做成了"底部居中"，这是错的。** 方形画布上看不出问题，但自适应图标只显示
中间 72/108 的圆 —— 居中文字正好落在这个圆的水平中轴上，视觉上就成了"贴在圆中央
偏下"的一块，既不像角标也不像水印，而是挡在画面正中。右下角才是署名该待的位置。

位置参数 `right_ratio` / `bottom_ratio` = **0.70**（不是贴边 0.9+）：

- 可见圆半径是 `n/3`，右下角要落在圆内需满足
  `(x1 - n/2)² + (y1 - n/2)² ≤ (n/3)²`，解得 45° 方向上最多到 **0.736n**
- 0.70 换算到显示区（只取中间 66.7%）是 **80%** 处，视觉上就是右下角
- 再往外就被启动器的圆形遮罩裁掉

`make-icon-plain.py` 跑完会自检署名是否落在可见圆内，超出直接 `SystemExit(1)`。
自检失败的典型表现不是"看不见"，而是**被裁成半截字**，那比不加更难看。

立绘是方形图、不受图标遮罩限制，所以用 `0.965` 真正贴右下角。

### 为什么是白字 + 深描边

图标底是纯白，白字只能靠描边显出轮廓；立绘底部实测平均亮度 79（偏深），
白字本身够亮。一组参数要在两头都对，所以描边宽度取字号的 7%。

描边宽度按**字号**算，不按画布 —— 按画布算的话 1024px 的图上会得到 9px 描边，
而字才 46px 高，描边比笔画还粗。

### 为什么背景层不加

自适应图标是 background 垫底、foreground 盖在上面，foreground 只在立绘处不透明。
署名画在背景层会被立绘遮掉大半，只在边缘露出残字。所以署名必须画在**前景层**。

### 为什么不做像素级不可见水印

试过，做不成：这套 DCT 水印需要 128 位，48px 图标只有 20 位容量；纯白背景层的
DCT 系数全为 0，扰动被 clip 到 255 直接丢失。而且"先给原图嵌再缩放"会让所有尺寸
全部失效（缩放打散 8×8 块）。

只给部分资源加等于给人"已经保护了"的错觉，比不加更糟。
**立绘仍是不可见水印 + 可见署名双份**（`tools/watermark.py`），图标只有可见署名。

## 可调参数

`make-icon-plain.py` 顶部：

| 参数 | 默认 | 说明 |
|---|---|---|
| `FILL` | 0.75 | 内容占画布比例。**注意**：0.75 会让约 37% 的非白像素落在可见圆外被裁掉；脚本 docstring 里写的"零裁切 ≈ 0.471"才是完全不裁的值 |
| `BG` | 纯白 | 画布底色 |
| `VISIBLE` | 72/108 | 可见区直径比例 |

`visible_sign.py` 顶部：

| 参数 | 默认 | 说明 |
|---|---|---|
| `TEXT_TINY` / `TEXT_ICON` / `TEXT_LARGE` | `©` / `© Ponko` / `© Ponko · visitor257` | 三种署名文案 |
| `MIN_WIDTH_FOR_TEXT` | 72 | 超过这个宽度才写字，否则只画 `©` |
| `TINY_SCALE` | 1.6 | 小图只画 `©` 时可以把字号放大，宽度不是约束 |
| `STROKE_RATIO` | 0.07 | 描边宽度 / 字号 |

图标实际调用的是 `make-icon-plain.py` 里的 `SIGN_KW`：

| 参数 | 值 | 说明 |
|---|---|---|
| `height_ratio` | 0.050 | 字号占画布高度。0.070 显得太大（第一版），在图标上很抢画面 |
| `right_ratio` | 0.70 | 右边界位置（受可见圆约束） |
| `bottom_ratio` | 0.70 | 底边位置（同上） |

立绘用 `height_ratio=0.032 / right_ratio=0.965 / bottom_ratio=0.965`，文案 `© Ponko · visitor257`。

## 已知问题（未修）

1. **`ic_launcher_round.png` 与 `ic_launcher.png` 逐像素相同**
   `make-icon-plain.py` 里两张图都 `save(full, ...)`。`circle()` 只用在预览图上。
   影响面小：只有 API 25 及以下、且用圆形图标的启动器会显示成方形；
   Android 8+ 走自适应图标不受影响。
2. **`FILL = 0.75` 会让画面约 37% 被圆形遮罩裁掉**（见上表）。属于取舍，未改。

## 立绘加署名的命令

立绘不是由上面的脚本生成的，要单独处理。**注意不要用 `watermark.py --from` 重跑**
（水印不可逆，重嵌会叠加劣化），而是读现有的（已带水印的）文件，画上署名再存：

```python
import sys; sys.path.insert(0, "tools/icon"); sys.path.insert(0, "tools")
from PIL import Image
import visible_sign as vs, watermark as wm

p = "app/src/main/res/drawable-nodpi/about_portrait.png"
vs.sign_visible(Image.open(p).convert("RGBA"), vs.TEXT_LARGE,
                height_ratio=0.032, right_ratio=0.965, bottom_ratio=0.965,
                fill=(255,255,255,235), stroke=(18,20,26,225)
                ).convert("RGB").save(p, "PNG", optimize=True)
print(wm.read_mark(p))   # 必须是 100%，否则水印被破坏了
```
