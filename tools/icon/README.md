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

### 位置：右下角（改过两版才对）

**第一版"底部居中"、第二版"底部右对齐"都还是偏中**，第三版才对。两次都错在同一个地方：
只看了右边界的位置，没看**文字本身的中心**。

- 第一版 `anchor="mb"`（水平居中）→ 中心就在 0.50，纯居中
- 第二版 `anchor="rb"` + 右边界 0.70 → 但 `© Ponko` 宽 0.21n，
  左边界回到 0.49n，**中心仍在 0.60**，视觉上还是偏中

现在两条约束一起满足：

| 约束 | 值 | 为什么 |
|---|---|---|
| 位置参数 | 显示区 **0.80** | 见下 |
| 文案 | 图标只用 `©` | `© Ponko` 宽 0.21n，右边界顶到 0.80 时中心只有 0.70；`©` 宽 0.06n，中心能到 0.77~0.79 |
| 判定 | **中心** ≥ 0.72 | 只看右边界会漏掉"文字很宽"这种情况 |

**位置为什么是 0.80 而不是贴边**：图标的可见区是**内切圆**，右下角是一段弧。

- 自适应前景层：只显示中间 72/108，圆半径 `n/3` → 45° 上限画布 **0.7356**
- 传统图标：整图显示，但启动器普遍套**内切圆**，半径 `0.5n` → 45° 上限画布 **0.8536**

两者换算回各自的**显示区坐标**都是 **0.8535**。所以同一个参数能用两层，
这是实测出来的巧合。取 0.80 留余量给描边和整数取整（0.83 时 48px 那档
的角正好压在圆上，浮点差一点就判超）。

`make-icon-plain.py` 跑完自检两件事，任一不过就 `SystemExit(1)`：
**① 在可见圆内**（否则被裁成半截字）、**② 墨迹中心在显示区 ≥ 0.72**（否则依然算"在中间"）。

立绘是方形图、没有启动器遮罩，用 `0.96` 真正贴角，中心落在 (0.83, 0.95)。

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
| `height_ratio` | 0.055 | 字号占画布高度。图标只写一个 `©`，可以比多字符时大 |
| `SIGN_TEXT` | `©` | 图标不含 "Ponko" 字样，原因见上表 |

`right_ratio` / `bottom_ratio` 不传给 `sign_visible`，让它走默认的 `display_room()`
（= 0.80 显示区坐标）。传了要自己保证不超圆。

立绘用 `height_ratio=0.028 / right_ratio=0.96 / bottom_ratio=0.96`，文案 `© Ponko · visitor257`。

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
                height_ratio=0.028, right_ratio=0.96, bottom_ratio=0.96,
                fill=(255,255,255,235), stroke=(18,20,26,225)
                ).convert("RGB").save(p, "PNG", optimize=True)
print(wm.read_mark(p))   # 必须是 100%，否则水印被破坏了
```
