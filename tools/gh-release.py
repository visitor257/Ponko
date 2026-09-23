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
TAG = "v1.6.0"
NAME = "Ponko v1.6.0"

BODY = """Ponko v1.6.0 - Offline-capable AI app for Android: chat (image + file input) + drawing + tagging.

All inference runs on-device: apart from the optional in-app LoRA download, no feature needs the network. No telemetry; your data stays on your phone.
See the [README](https://github.com/visitor257/Ponko#readme).

## v1.6.0 Highlights
- **Multi-file (split) drawing models**: import a model as a set of files (unet + text encoders + VAE) and fill in the slots one by one; each model set keeps its files in its own folder, can be selected or long-pressed to delete, and the slot panel is collapsible so the page stays tidy
- **Fix**: deleting a LoRA no longer wipes all of them - tap to select, long-press to remove just that one
- **SDXL is single-file only** (upstream sd.cpp cannot combine a split SDXL): the model-type menu no longer offers it, and the app explains why
- Clearer load-failure message when a drawing model cannot be created
- Note: multi-file is really meant for families that upstream ships split apart (Flux / SD3.5 / Qwen-Image); the smallest such sets are 8 GB+, so on a phone single-file models remain the practical choice
- Version 1.5.0 -> 1.6.0 (versionCode 10)

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
- Built-in custom stable-diffusion.cpp; loads GGUF drawing models - single-file all-in-one (SD1.5 / SD2 / SDXL) or a multi-file (split) set filled in slot by slot; CPU by default, optional GPU (Vulkan) acceleration with automatic CPU fallback
- GPU status: only drawing (Vulkan), tagging (NNAPI) and `.litertlm` chat carry GPU code; all build-time-verified only, gguf chat is CPU-only
- Text-to-image + image-to-image (denoise strength 0.05-0.99, output size auto-aligned to the reference)
- Per-page parameter defaults with confirmation (separate sets for LoRA on/off)
- LCM-LoRA acceleration: one-tap download in-app (Hugging Face official / hf-mirror), cuts 20 steps to 4-8; local LoRA import supported
- Generate from chat: when both an LLM and an SD model are loaded, just say "draw a ..."
- **Image tagging (Tagger)**: on-device ONNX tagger (WD14-style), tags can be forwarded to T2I / I2I

## Install
- arm64-v8a only (64-bit ARM devices), minSdk 28 (Android 9+)
- v1.6.0 (versionCode 10) installs over v1.5.0 / v1.4.1 / v1.4.0 / v1.3.x / v1.2; uninstall older debug builds or v1.0 first (different signing key)
- Model files are not bundled: import chat models (`.litertlm` / `.gguf`), a drawing model (GGUF: single file, or a multi-file set) and, optionally, a tagger (`.onnx` + `.csv`) from the in-app "Models" page

## License
Code is MIT; art assets (icons, artwork) are all rights reserved. Third-party components (stable-diffusion.cpp, ggml, Vulkan-Hpp/Vulkan-Headers, LiteRT-LM, llama.cpp, Markwon, ONNX Runtime, etc.) are distributed under their respective licenses - see the repository NOTICE. ONNX Runtime bundles extra third-party components; their notices are included under `licenses/`. The native runtimes also statically link XNNPACK, protobuf, re2, cpuinfo, zlib and others; see NOTICE and the `licenses/` directory for the full list.

---

Ponko v1.6.0 —— 本地 AI App（Android）：聊天（可发图、可发文件）+ 绘图 + 打标

所有推理都在本机完成：除了「手动下载 LoRA」，其余功能都不需要联网；无遥测，数据只留在设备本地。

### v1.6.0 亮点
- **绘图模型支持多文件（拆包）导入**：按家族逐个补槽位（unet + 文本编码器 + VAE），每个模型集的文件放在各自子目录，列表可点选 / 长按删除，槽位面板可折叠，页面不再被撑长
- **修复**：删除 LoRA 不再一次清空全部——点击选用、长按只删那一个
- **SDXL 限定单文件**（上游 sd.cpp 无法组合拆开的 SDXL）：类型菜单不再提供，并在界面里说明原因
- 绘图模型加载失败的提示更明确
- 说明：多文件真正有意义的是「官方本来就拆开分发」的家族（Flux / SD3.5 / Qwen-Image），这类最小组合 8GB+，手机上仍建议用单文件
- 版本 1.5.0 -> 1.6.0（versionCode 10）

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
- 内置自编 stable-diffusion.cpp；读 GGUF 格式的绘图模型——单文件整合版（SD1.5 / SD2 / SDXL）或按槽位补齐的多文件（拆包）组合；默认 CPU，可选 GPU（Vulkan）加速，失败自动回退 CPU
- GPU 现状：只有绘图（Vulkan）、打标（NNAPI）、对话 `.litertlm` 三条带 GPU 代码，且都仅构建侧验证；对话 GGUF 只能 CPU
- 文生图 + 图生图（重绘强度 0.05~0.99，尺寸自动对齐参考图）
- 每个参数页可「设为默认值 / 恢复默认」（带确认），文生图 / 图生图按 LoRA 开 / 关各存一套
- LCM-LoRA 加速：App 内一键下载（HF 官方 / hf-mirror 双源），20 步压到 4~8 步；也支持导入本地 LoRA
- 对话页出图：语言模型与绘图模型同时加载时，直接说「画一张…」就会调用绘图模型
- **图像打标（Tagger）**：本机离线 ONNX 打标（WD14 系），标签可发送至文生图 / 图生图

### 安装
- 仅支持 arm64-v8a（64 位 ARM 真机），minSdk 28（Android 9 及以上）
- v1.6.0（versionCode 10）可直接覆盖安装 v1.5.0 / v1.4.1 / v1.4.0 / v1.3.x / v1.2；更早的 debug 版或 v1.0 请先卸载（签名不同）
- 模型文件需自备：对话模型（`.litertlm` / `.gguf`）、绘图模型（GGUF：单文件或多文件组合），打标模型（`.onnx` + `.csv`）可选，都在 App 内「模型」页导入

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
