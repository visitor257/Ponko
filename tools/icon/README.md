# 图标生成

- `source.png` —— 图标原图（1920×1920，白底立绘）
- `make-icon.py` —— 生成脚本

## 作用

抠掉白底 → 内容限制在自适应图标安全区内 → 加深蓝背景与边缘渐隐 → 输出完整的 Android 图标资源：

```
out/
├── res/
│   ├── drawable-{mdpi..xxxhdpi}/ic_launcher_{foreground,background,monochrome}.png
│   ├── mipmap-{mdpi..xxxhdpi}/ic_launcher{,_round}.png
│   └── mipmap-anydpi-v26/ic_launcher{,_round}.xml
└── preview/          圆形 / 圆角方形 / 深色壁纸 / 真实 48px 预览
```

## 用法

```bash
pip install pillow numpy
python make-icon.py
# 然后把 out/res/* 覆盖到 app/src/main/res/
```

也可用环境变量指定输入输出：`ICON_SRC` / `ICON_OUT`。

## 可调参数（文件顶部）

| 参数 | 默认 | 说明 |
|---|---|---|
| `FILL` | 0.60 | 内容较长边占画布比例。调大更饱满，但四角更容易被圆形遮罩裁到 |
| `SAFE` | 66/108 | 自适应图标安全区直径比例 |
| `VISIBLE` | 72/108 | 可见区直径比例（各种遮罩都不会超出这个圆） |

## 设计约束（换图时请遵守）

1. 主体居中，四周留白 —— 内容超出中间 61% 安全区的部分会被遮罩裁掉
2. 关键元素别放在画面四角（左上/右上/左下/右下最容易被圆裁）
3. 原图被硬切掉的边缘（如身体、发梢）脚本会做渐隐处理
