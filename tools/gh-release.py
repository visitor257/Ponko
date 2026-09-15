# -*- coding: utf-8 -*-
"""在 GitHub 上为 visitor257/Ponko 创建 Release 并上传 APK 附件。
Release 说明为中英双语（英文在前，中文在后）。"""
import json
import os
import re
import sys
import urllib.request

REPO = "visitor257/Ponko"
TOKFILE = r"C:\Users\Administrator\Desktop\git_repo_tok.txt"
APK = r"C:\Users\Administrator\.qclaw\workspace-agent-e522fb09\LiteRT-Chat\app\build\outputs\apk\release\Ponko-release.apk"
TAG = "v1.3.1"
NAME = "Ponko v1.3.1"

BODY = """Ponko v1.3.1 - Fully offline AI app for Android: chat (now with image input) + drawing + tagging.

All inference runs on-device. No network, no telemetry; your data stays on your phone.
See the [README](https://github.com/visitor257/Ponko#readme).

## v1.3.1 Highlights
- **Chat: send images (multimodal input)**
  - New **+** button left of the send button opens an upward drawer with **Camera / Gallery**
  - Up to **4 images** per message; picked images are re-encoded to JPEG and kept locally
  - Your images show as thumbnails in the bubble and are restored from the local history
  - `.litertlm` multimodal models (e.g. Gemma 3n) work out of the box
  - `.gguf` vision models work once you import the matching **mmproj** file on the Models page
  - Text-only models are rejected with a clear hint instead of failing
- **gguf vision support (mmproj)**
  - Pick a vision projector (`mmproj-*.gguf`) on the Models page; it is attached with `setMmproj()` when the model loads
  - Imported mmproj files are listed and **switchable**, with a **No mmproj (text-only)** option to go back
  - Long-press an entry to delete it; reload the model after switching
- **Full-screen image viewer**
  - Tap any chat image (yours or the one the model drew) to view it full-screen; tap anywhere to close
  - Long-press to save it to the gallery
- **Fixes**
  - You can pick an image while the model is still generating (sending still waits for the current answer)
  - Scrolling up to read history is no longer yanked back to the bottom while the model streams
- Version 1.3.0 -> 1.3.1 (versionCode 6)

## Chat
- Two backends: LiteRT-LM (`.litertlm`) + llama.cpp (`.gguf`)
- **Image input**: `.litertlm` multimodal models, or `.gguf` vision models with a matching mmproj
- Multiple conversations, history persisted locally
- Reasoning and answer shown separately, collapsible; the thinking toggle works for both formats
- Streaming Markdown rendering (headings / lists / tables / code / links)
- Keep typing while generating; interrupt and continue; one-tap "regenerate" (random seed)
- Auto-follow scroll, pause on scroll-up, jump-to-bottom button

## Drawing
- Built-in custom stable-diffusion.cpp, loads SD1.5-family GGUF models, pure CPU
- Text-to-image + image-to-image
- LCM-LoRA acceleration: one-tap download in-app (Hugging Face official / hf-mirror), cuts 20 steps to 4-8
- Generate from chat: when both an LLM and an SD model are loaded, just say "draw a ..."
- Width / height / steps / CFG / seed / sampler / scheduler all adjustable
- **Image tagging (Tagger)**: offline ONNX tagger (WD14-style), tags can be forwarded to T2I / I2I

## Install
- arm64-v8a only (64-bit ARM devices), minSdk 28 (Android 9+)
- v1.3.1 (versionCode 6) installs over v1.3 / v1.2; uninstall older debug builds or v1.0 first (different signing key)
- Model files are not bundled: import chat models (`.litertlm` / `.gguf`), a drawing model (SD1.5 GGUF) and, optionally, a tagger (`.onnx` + `.csv`) from the in-app "Models" page

## License
Code is MIT; art assets (icons, artwork) are all rights reserved. Third-party components (stable-diffusion.cpp, ggml, LiteRT-LM, llama.cpp, Markwon, ONNX Runtime, etc.) are distributed under their respective licenses - see the repository NOTICE. ONNX Runtime bundles extra third-party components; their notices are included under `licenses/`. The native runtimes also statically link XNNPACK, protobuf, re2, cpuinfo, zlib and others; see NOTICE and the `licenses/` directory for the full list.

---

Ponko v1.3.1 —— 本地 AI App（Android）：聊天（可发图）+ 绘图 + 打标

所有推理都在本机离线运行，不联网、无遥测，你的数据只留在设备本地。

### v1.3.1 亮点
- **聊天发送图片（多模态输入）**
  - 发送键左边新增「**+**」按钮，向上展开抽屉：**拍照 / 图库**
  - 一条消息最多 **4 张图**；选中的图会重新编码为 JPEG 存在本机
  - 你发的图会在气泡里显示缩略图，并随本地历史一起恢复
  - `.litertlm` 多模态模型（如 Gemma 3n）直接可用
  - `.gguf` 视觉模型：在模型页导入配套的 **mmproj** 文件后即可发图
  - 纯文字模型会被明确拦下并提示，不会报错崩溃
- **gguf 视觉模型（mmproj）**
  - 在模型页选择视觉投影文件（`mmproj-*.gguf`），加载模型时经 `setMmproj()` 挂载
  - 已导入的 mmproj 以列表呈现，**可切换**，并有「**不使用 mmproj（纯文字）**」一项可以退回
  - 长按可删除；切换后重新加载模型生效
- **图片全屏查看**
  - 点击聊天里的任意图片（你发的、模型画的）即可全屏查看，点任意处关闭
  - 长按保存到相册
- **修复**
  - 模型生成过程中也能先选好图片（发送仍会等当前回答结束）
  - 上滑看历史时，不再被流式输出拽回底部
- 版本 1.3.0 -> 1.3.1（versionCode 6）

### 对话功能
- 双后端：LiteRT-LM（`.litertlm`）+ llama.cpp（`.gguf`）
- **图片输入**：`.litertlm` 多模态模型，或配了 mmproj 的 `.gguf` 视觉模型
- 多对话管理，历史本地持久化
- 思考过程与正文分离、可折叠；思考开关对两种格式均生效
- Markdown 流式渲染（标题 / 列表 / 表格 / 代码块 / 链接）
- 生成中可继续打字；中断后可继续对话；一键「重新生成」（随机种子）
- 自动跟随滚动，上滑暂停、一键回到底部

### 绘图功能
- 内置自编 stable-diffusion.cpp，读 GGUF 格式的 SD1.5 系模型，纯 CPU 绘制
- 文生图 + 图生图两种模式
- LCM-LoRA 加速：App 内一键下载（HF 官方 / hf-mirror 双源），20 步压到 4~8 步
- 对话页出图：语言模型与绘图模型同时加载时，直接说「画一张…」就会调用绘图模型
- 宽高 / 步数 / CFG / 种子 / 采样器 / 调度器全可调
- **图像打标（Tagger）**：离线 ONNX 打标（WD14 系），标签可发送至文生图 / 图生图

### 安装
- 仅支持 arm64-v8a（64 位 ARM 真机），minSdk 28（Android 9 及以上）
- v1.3.1（versionCode 6）可直接覆盖安装 v1.3 / v1.2；更早的 debug 版或 v1.0 请先卸载（签名不同）
- 模型文件需自备：对话模型（`.litertlm` / `.gguf`）、绘图模型（SD1.5 GGUF），打标模型（`.onnx` + `.csv`）可选，都在 App 内「模型」页导入

### 许可
代码 MIT；美术资源（图标、立绘）版权归作者所有。本项目包含的第三方组件（stable-diffusion.cpp、ggml、LiteRT-LM、llama.cpp、Markwon、ONNX Runtime 等）按各自许可证分发，详见仓库 NOTICE。ONNX Runtime 另自带若干第三方组件，其声明收录在 `licenses/` 目录。各运行时原生库还静态链入 XNNPACK、protobuf、re2、cpuinfo、zlib 等，完整清单见 NOTICE 与 `licenses/` 目录。
"""


def token():
    txt = open(TOKFILE, encoding="utf-8", errors="replace").read()
    return re.search(r"ghp_\w+", txt).group(0)


def req(url, data=None, ctype=None, method=None, timeout=180):
    h = {
        "Authorization": "token " + token(),
        "Accept": "application/vnd.github+json",
        "User-Agent": "ponko-release",
    }
    if ctype:
        h["Content-Type"] = ctype
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    return urllib.request.urlopen(r, timeout=timeout)


def _head_sha():
    """返回本地 HEAD 的完整 sha，避免 GitHub 用远端 main 的旧 HEAD 打 tag。"""
    import subprocess
    r = subprocess.run('git rev-parse HEAD', cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       capture_output=True, encoding="utf-8", errors="replace", shell=True)
    return r.stdout.strip().splitlines()[0]


def main():
    api = "https://api.github.com/repos/" + REPO

    # 已存在同名 release 就先删掉，保证可重复执行
    try:
        for rel in json.loads(req(api + "/releases?per_page=100").read()):
            if rel.get("tag_name") == TAG:
                print("删除已存在的 release:", rel["id"])
                req(api + "/releases/" + str(rel["id"]), method="DELETE").read()
    except Exception as e:
        print("检查已有 release 出错（忽略）:", e)

    payload = json.dumps({
        "tag_name": TAG,
        "target_commitish": _head_sha(),
        "name": NAME,
        "body": BODY,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    rel = json.loads(req(api + "/releases", data=payload, ctype="application/json", method="POST").read())
    print("release 已创建:", rel["html_url"])
    rid = rel["id"]

    data = open(APK, "rb").read()
    print("APK 大小: %.2f MB" % (len(data) / 1048576.0))
    up = "https://uploads.github.com/repos/%s/releases/%d/assets?name=Ponko-release.apk" % (REPO, rid)
    asset = json.loads(req(up, data=data, ctype="application/vnd.android.package-archive", method="POST").read())
    print("附件已上传:", asset["name"], asset["size"], "bytes")
    print("下载地址:", asset["browser_download_url"])


if __name__ == "__main__":
    main()
