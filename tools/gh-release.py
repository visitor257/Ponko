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
TAG = "v1.5.0"
NAME = "Ponko v1.5.0"

BODY = """Ponko v1.5.0 - Offline-capable AI app for Android: chat (image + file input) + drawing + tagging.

All inference runs on-device: apart from the optional in-app LoRA download, no feature needs the network. No telemetry; your data stays on your phone.
See the [README](https://github.com/visitor257/Ponko#readme).

## v1.5.0 Highlights
- **GPU support (optional; verified at build time only, NOT tested on a real device)**
  - **Drawing**: a Vulkan backend is now compiled into the app. Set the run mode to GPU on the Models page and drawing uses the device's Vulkan backend (text-to-image and image-to-image both); if the phone has no Vulkan or init fails, Ponko falls back to CPU automatically and the drawing status line shows the backend actually in use
  - **Chat `.litertlm`**: GPU was already wired up; it now falls back to CPU automatically when init fails instead of erroring out
  - **Tagging**: NNAPI when GPU is selected (unchanged)
  - **Only these three paths carry GPU code**, and all three have only been verified at build time, never on a real device - they are expected to work in theory, but whether they do depends on the phone's drivers and VRAM. **GGUF chat has no GPU backend at all and always runs on CPU**
  - The package grows accordingly: 39.98 MB -> 50.66 MB (`libstable-diffusion.so` 8.9 MB -> 44 MB, mostly precompiled SPIR-V shaders)
- Licence bookkeeping for the new build dependency: Vulkan-Hpp / Vulkan-Headers (Apache-2.0 OR MIT) is vendored with the source and declared in NOTICE, the READMEs and the in-app About page
- Version 1.4.1 -> 1.5.0 (versionCode 9)

## Chat
- Two backends: LiteRT-LM (`.litertlm`) + llama.cpp (`.gguf`)
- **Image input**: `.litertlm` multimodal models, or `.gguf` vision models with a matching mmproj
- **File input**: text-like files, up to 2 per message
- **Tagger as a chat tool**: a loaded tagger can be called from chat when the chat model cannot see images itself
- Multiple conversations, history persisted locally
- Reasoning and answer shown separately, collapsible; the thinking toggle works for both formats
- Streaming Markdown rendering (headings / lists / tables / code / links)
- Keep typing while generating; interrupt and continue; one-tap "regenerate" (keeps images and files)
- Auto-follow scroll, pause on scroll-up, jump-to-bottom button

## Drawing
- Built-in custom stable-diffusion.cpp, loads SD1.5-family GGUF models; CPU by default, optional GPU (Vulkan) acceleration with automatic CPU fallback
- GPU status: only drawing (Vulkan), tagging (NNAPI) and `.litertlm` chat carry GPU code; all build-time-verified only, gguf chat is CPU-only
- Text-to-image + image-to-image (denoise strength 0.05-0.99, output size auto-aligned to the reference)
- Per-page parameter defaults with confirmation (separate sets for LoRA on/off)
- LCM-LoRA acceleration: one-tap download in-app (Hugging Face official / hf-mirror), cuts 20 steps to 4-8; local LoRA import supported
- Generate from chat: when both an LLM and an SD model are loaded, just say "draw a ..."
- **Image tagging (Tagger)**: on-device ONNX tagger (WD14-style), tags can be forwarded to T2I / I2I

## Install
- arm64-v8a only (64-bit ARM devices), minSdk 28 (Android 9+)
- v1.5.0 (versionCode 9) installs over v1.4.1 / v1.4.0 / v1.3.x / v1.2; uninstall older debug builds or v1.0 first (different signing key)
- Model files are not bundled: import chat models (`.litertlm` / `.gguf`), a drawing model (SD1.5 GGUF) and, optionally, a tagger (`.onnx` + `.csv`) from the in-app "Models" page

## License
Code is MIT; art assets (icons, artwork) are all rights reserved. Third-party components (stable-diffusion.cpp, ggml, Vulkan-Hpp/Vulkan-Headers, LiteRT-LM, llama.cpp, Markwon, ONNX Runtime, etc.) are distributed under their respective licenses - see the repository NOTICE. ONNX Runtime bundles extra third-party components; their notices are included under `licenses/`. The native runtimes also statically link XNNPACK, protobuf, re2, cpuinfo, zlib and others; see NOTICE and the `licenses/` directory for the full list.

---

Ponko v1.5.0 —— 本地 AI App（Android）：聊天（可发图、可发文件）+ 绘图 + 打标

所有推理都在本机完成：除了「手动下载 LoRA」，其余功能都不需要联网；无遥测，数据只留在设备本地。

### v1.5.0 亮点
- **GPU 支持（可选；仅构建侧验证，未真机实测）**
  - **绘图**：新增编译进包的 Vulkan 后端。在「模型」页把运行方式切到 GPU，绘图就会走设备的 Vulkan 后端（文生图 / 图生图都生效）；机型没有 Vulkan 或初始化失败会自动回退 CPU，绘图页状态行会显示实际使用的后端
  - **对话 `.litertlm`**：GPU 原本已接线，现在初始化失败会**自动退回 CPU**，不再直接报错
  - **打标**：选 GPU 时走 NNAPI（未变）
  - **只有这三条路径带 GPU 代码**，且三者都只在构建侧验证过、**未在任何手机上实测**——理论上可用，实际取决于机型驱动与显存。**对话 GGUF 没有任何 GPU 后端，始终 CPU**
  - 包体相应变大：39.98 MB -> 50.66 MB（`libstable-diffusion.so` 8.9 MB -> 44 MB，主要是预编译的 SPIR-V 着色器）
- 新增构建依赖的许可登记：Vulkan-Hpp / Vulkan-Headers（Apache-2.0 OR MIT）随源码入库，已写入 NOTICE / README / App 关于页
- 版本 1.4.1 -> 1.5.0（versionCode 9）

### 对话功能
- 双后端：LiteRT-LM（`.litertlm`）+ llama.cpp（`.gguf`）
- **图片输入**：`.litertlm` 多模态模型，或配了 mmproj 的 `.gguf` 视觉模型
- **文件输入**：文本类文件，一条最多 2 个
- **打标当工具用**：对话模型看不见图片时，可在聊天里调用已加载的打标模型
- 多对话管理，历史本地持久化
- 思考过程与正文分离、可折叠；思考开关对两种格式均生效
- Markdown 流式渲染（标题 / 列表 / 表格 / 代码块 / 链接）
- 生成中可继续打字；中断后可继续对话；一键「重新生成」（会带上图片与文件）
- 自动跟随滚动，上滑暂停、一键回到底部

### 绘图功能
- 内置自编 stable-diffusion.cpp，读 GGUF 格式的 SD1.5 系模型；默认 CPU，可选 GPU（Vulkan）加速，失败自动回退 CPU
- GPU 现状：只有绘图（Vulkan）、打标（NNAPI）、对话 `.litertlm` 三条带 GPU 代码，且都仅构建侧验证；对话 GGUF 只能 CPU
- 文生图 + 图生图（重绘强度 0.05~0.99，尺寸自动对齐参考图）
- 每个参数页可「设为默认值 / 恢复默认」（带确认），文生图 / 图生图按 LoRA 开 / 关各存一套
- LCM-LoRA 加速：App 内一键下载（HF 官方 / hf-mirror 双源），20 步压到 4~8 步；也支持导入本地 LoRA
- 对话页出图：语言模型与绘图模型同时加载时，直接说「画一张…」就会调用绘图模型
- **图像打标（Tagger）**：本机离线 ONNX 打标（WD14 系），标签可发送至文生图 / 图生图

### 安装
- 仅支持 arm64-v8a（64 位 ARM 真机），minSdk 28（Android 9 及以上）
- v1.5.0（versionCode 9）可直接覆盖安装 v1.4.1 / v1.4.0 / v1.3.x / v1.2；更早的 debug 版或 v1.0 请先卸载（签名不同）
- 模型文件需自备：对话模型（`.litertlm` / `.gguf`）、绘图模型（SD1.5 GGUF），打标模型（`.onnx` + `.csv`）可选，都在 App 内「模型」页导入

### 许可
代码 MIT；美术资源（图标、立绘）版权归作者所有。本项目包含的第三方组件（stable-diffusion.cpp、ggml、Vulkan-Hpp/Vulkan-Headers、LiteRT-LM、llama.cpp、Markwon、ONNX Runtime 等）按各自许可证分发，详见仓库 NOTICE。ONNX Runtime 另自带若干第三方组件，其声明收录在 `licenses/` 目录。各运行时原生库还静态链入 XNNPACK、protobuf、re2、cpuinfo、zlib 等，完整清单见 NOTICE 与 `licenses/` 目录。
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
    """取远端 main 的当前 HEAD。

    注意：push-via-api.py 是通过 Git Data API 重建提交的，远端 sha 与本地并不相同，
    本地 sha 在远端根本不存在，不能拿来当 target_commitish（tag 会建失败）。
    """
    r = req("https://api.github.com/repos/" + REPO + "/git/ref/heads/main")
    return json.loads(r.read())["object"]["sha"]


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

    # 同名 tag 已存在时 GitHub 会沿用旧 tag（不会移到新的 target_commitish），先删掉
    try:
        req(api + "/git/refs/tags/" + TAG, method="DELETE").read()
        print("删除已存在的 tag:", TAG)
    except Exception as e:
        print("删除 tag（不存在则忽略）:", e)

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
