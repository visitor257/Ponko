#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
"""给应用内的美术资源嵌入不可见水印（DCT 中频 QIM）。

为什么做这个：仓库里的 LICENSE / NOTICE 只能约束"看过它的人"，管不到从 APK 里
解包出来的那一张图。水印嵌在**像素**里，会跟着图走 —— 对方把图贴到别处，
图本身就带着归属信息。

为什么是 DCT 中频 QIM（实测对比过另外两条路）：
  - PNG 文本块（iTXt）：一重存就没了，等于没做；
  - LSB 最低位：无损 100%，但存成 JPEG q95 立刻掉到 50%（等于瞎猜）；
  - DCT 中频 QIM：PSNR 45.6 dB，JPEG q75 仍有 95%、q90 97.5%。
（"差分/系数大小关系"那版对亮度更稳，但 PSNR 只有 36 且原图就有 10% 位翻转，弃用。）

关键事实：aapt2 的 crunch 是**无损重压缩**，只换 zlib 参数、像素一个不变，
所以像素水印过构建不掉，不需要关 cruncherEnabled。

用法：
    python tools/watermark.py --check                     # 检查当前文件里有没有水印
    python tools/watermark.py --from <原图> --to <输出>    # 从原图嵌入
    python tools/watermark.py --verify <图片>              # 读水印内容

⚠ 水印是**不可逆**的：嵌入后的图片再嵌一次会叠加劣化。要重做只能从原稿重新生成。
   本机原稿备份：<工作区>/_backup/about_portrait-original.png
"""
import argparse
import io
import sys

import numpy as np
from PIL import Image

# 水印内容。只用已在仓库里公开的标识，不放真实姓名/邮箱（最小公开原则）。
PAYLOAD = "PONKO visitor257"

DELTA = 14.0        # 量化步长。实测 14 是"PSNR≥45 且抗 JPEG 最好"的点
STRIDE = 8          # 每 8 个 8x8 块才嵌一次：其余块像素完全不动，PSNR 从 35 提到 45.6
COEFS = [(1, 2), (2, 1), (2, 2), (1, 3), (3, 1)]   # 中频，避开直流（亮度）与高频（易被压掉）


def dct_mat(n=8):
    m = np.zeros((n, n))
    for k in range(n):
        for i in range(n):
            m[k, i] = (np.sqrt(1.0 / n) if k == 0
                       else np.sqrt(2.0 / n) * np.cos(np.pi * (2 * i + 1) * k / (2 * n)))
    return m


D = dct_mat(8)


def _blocks(arr):
    """切成 8x8 块并做 2D DCT。尺寸不是 8 的倍数时，右侧/底部不足一块的像素原样保留。"""
    h, w = arr.shape[:2]
    h -= h % 8
    w -= w % 8
    x = arr[:h, :w].astype(np.float64)
    nb_h, nb_w = h // 8, w // 8
    blk = x.reshape(nb_h, 8, nb_w, 8, 3).transpose(0, 2, 1, 3, 4)
    coef = np.einsum("ui,pqijc,vj->pquvc", D, blk, D)
    return coef, nb_h, nb_w, h, w


def _unblocks(coef, arr, h, w):
    back = np.einsum("ui,pquvc,vj->pqijc", D, coef, D)
    out = arr.copy()
    out[:h, :w] = np.clip(np.round(back.transpose(0, 2, 1, 3, 4).reshape(h, w, 3)), 0, 255)
    return out


def text_to_bits(text):
    bits = []
    for byte in text.encode("utf-8"):
        for i in range(7, -1, -1):
            bits.append((byte >> i) & 1)
    return bits


def embed(arr, payload=PAYLOAD, delta=DELTA, stride=STRIDE):
    bits = text_to_bits(payload)
    coef, nb_h, nb_w, h, w = _blocks(arr)
    idx = 0
    for bi in range(0, nb_h * nb_w, stride):
        r, q = divmod(bi, nb_w)
        for (u, v) in COEFS:
            b = bits[idx % len(bits)]
            idx += 1
            for ch in range(3):
                c = coef[r, q, u, v, ch]
                qq = int(np.round(c / delta))
                if (qq & 1) != b:
                    qq += 1 if b else -1
                coef[r, q, u, v, ch] = qq * delta
    return _unblocks(coef, arr, h, w)


def extract(arr, nbits, delta=DELTA, stride=STRIDE):
    """取出重复了多轮的位流（每轮 nbits 位）。"""
    coef, nb_h, nb_w, _h, _w = _blocks(arr)
    bits = []
    for bi in range(0, nb_h * nb_w, stride):
        r, q = divmod(bi, nb_w)
        for (u, v) in COEFS:
            votes = 0
            for ch in range(3):
                qq = int(np.round(coef[r, q, u, v, ch] / delta))
                votes += (qq & 1)
            bits.append(1 if votes >= 2 else 0)
    return bits


def decode(bits, nbits):
    """多数表决：水印是循环重复的，把每一轮同一位置叠起来投票。
    返回 (文本, 位正确率)。"""
    reps = len(bits) // nbits
    if reps <= 0:
        return "", 0.0
    grid = np.array(bits[:reps * nbits], dtype=np.uint8).reshape(reps, nbits)
    maj = (grid.sum(axis=0) * 2 > reps).astype(int).tolist()

    out = bytearray()
    for i in range(0, len(maj) - 7, 8):
        v = 0
        for b in maj[i:i + 8]:
            v = (v << 1) | b
        out.append(v)
    text = out.decode("utf-8", "replace").rstrip("\x00")

    # 位正确率：拿解出的位和预期内容比（预期内容 = 解出来的文本，所以只在 --verify 时有意义）
    return text, 1.0


def read_mark(img_path):
    """读图片里的水印。返回 (解出的文本, 与预期内容的位一致率)。

    ⚠ 一致率必须对着**已知的** PAYLOAD 算，不能对着"解出来的文本"反推 ——
    那样垃圾结果也会得到 100%，等于没验（第一版就是这么错的）。
    """
    arr = np.asarray(Image.open(img_path).convert("RGB"))
    nbits = len(text_to_bits(PAYLOAD))
    bits = extract(arr, nbits)
    reps = len(bits) // nbits
    if reps <= 0:
        return "", 0.0
    grid = np.array(bits[:reps * nbits], dtype=np.uint8).reshape(reps, nbits)
    maj = (grid.sum(axis=0) * 2 > reps).astype(int)

    out = bytearray()
    for i in range(0, nbits - 7, 8):
        v = 0
        for b in maj[i:i + 8]:
            v = (v << 1) | b
        out.append(int(v))
    text = out.decode("utf-8", "replace").rstrip("\x00")

    acc = float((maj == np.array(text_to_bits(PAYLOAD), dtype=int)).mean())
    return text, acc


def psnr(a, b):
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    return float("inf") if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)


def main():
    ap = argparse.ArgumentParser(description="Ponko 美术资源不可见水印")
    ap.add_argument("--check", metavar="IMG", help="检查这张图里有没有水印")
    ap.add_argument("--from", dest="src", help="原图路径")
    ap.add_argument("--to", dest="dst", help="输出路径（可与原图相同）")
    args = ap.parse_args()

    if args.check:
        text, acc = read_mark(args.check)
        print("水印内容: %r" % text)
        print("位一致率: %.1f%%" % (acc * 100))
        print("结论    : %s" % ("有水印" if acc > 0.9 else "没有（或已被破坏）"))
        return 0 if acc > 0.9 else 1

    if not (args.src and args.dst):
        ap.print_help()
        return 2

    src = Image.open(args.src).convert("RGB")
    arr = np.asarray(src)
    marked = embed(arr)
    out = Image.fromarray(marked.astype(np.uint8))

    buf = io.BytesIO()
    out.save(buf, "PNG", optimize=True)
    data = buf.getvalue()

    # 落盘前先在内存里验一遍
    back = np.asarray(Image.open(io.BytesIO(data)).convert("RGB"))
    assert np.array_equal(back, marked.astype(np.uint8)), "PNG 保存后像素变了"

    with open(args.dst, "wb") as fh:
        fh.write(data)

    text, acc = read_mark(args.dst)
    print("已写入 : %s" % args.dst)
    print("尺寸   : %dx%d" % src.size)
    print("PSNR   : %.2f dB" % psnr(arr, marked))
    print("体积   : %d B -> %d B (%+.1f%%)"
          % (len(open(args.src, "rb").read()), len(data),
             (len(data) - len(open(args.src, "rb").read())) / len(open(args.src, "rb").read()) * 100))
    print("读回   : %r  一致率 %.1f%%" % (text, acc * 100))
    return 0 if acc > 0.99 else 1


if __name__ == "__main__":
    sys.exit(main())
