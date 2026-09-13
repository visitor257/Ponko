# -*- coding: utf-8 -*-
"""在 GitHub 上为 visitor257/Ponko 创建 Release 并上传 APK 附件。

Release 说明为中英双语（英文在前，中文在后）。
"""
import json
import os
import re
import sys
import urllib.request

REPO = "visitor257/Ponko"
TOKFILE = r"C:\Users\Administrator\Desktop\git_repo_tok.txt"
APK = r"C:\Users\Administrator\.qclaw\workspace-agent-e522fb09\LiteRT-Chat\app\build\outputs\apk\release\Ponko-release.apk"
TAG = "v1.3"
NAME = "Ponko v1.3"

BODY = """Ponko v1.3 — Fully offline AI app for Android: chat + drawing + tagging.

All inference runs on-device. No network, no telemetry; your data stays on your phone.
See the [README](https://github.com/visitor257/Ponko#readme).

## v1.3 Highlights
- **Image tagging (Tagger)**: reverse an image into Danbooru-style tags, fully offline
  - Import a WD14-style ONNX tagger (`.onnx`) plus its tag list (`selected_tags.csv`) yourself — nothing is bundled or downloaded
  - New **Tagger** tab in the drawing parameters: pick an image, set threshold / max tags, then get tags
  - Send the tags to **Text-to-Image** or **Image-to-Image** (with a confirmation prompt)
  - On the result page you can send a generated image to **Image-to-Image** (as the reference) or to **Tagger** (as its input)
  - Runs on ONNX Runtime and follows the Models page run mode: CPU, or NNAPI when GPU is selected
- **Parameter defaults**: each parameter page now has "Set as default" and "Restore defaults"
  - Text-to-Image and Image-to-Image keep **separate default sets for LoRA on/off**; toggling the LoRA switch applies the matching set
- **Model import**: the drawing model is now imported as a **single `.gguf` file** (no more folder import); added **local LoRA import** (`.safetensors`, single file)
- Version 1.2.0 -> 1.3.0 (versionCode 5)

## Chat
- Two backends: LiteRT-LM (`.litertlm`) + llama.cpp (`.gguf`)
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
- **Image tagging (Tagger)**: offline WD14-style ONNX tagger, tags can be forwarded to T2I / I2I

## Install
- arm64-v8a only (64-bit ARM devices), minSdk 28 (Android 9+)
- v1.3 (versionCode 5) installs over v1.2; uninstall older debug builds or v1.0 first (different signing key)
- Model files are not bundled: import chat models (`.litertlm` / `.gguf`), a drawing model (SD1.5 GGUF) and, optionally, a tagger (`.onnx` + `.csv`) from the in-app "Models" page

## License
Code is MIT; art assets (icons, artwork) are all rights reserved. Third-party components (stable-diffusion.cpp, ggml, LiteRT-LM, llama.cpp, Markwon, ONNX Runtime, etc.) are distributed under their respective licenses — see the repository NOTICE. ONNX Runtime bundles extra third-party components; their notices are included under `licenses/`. The native runtimes also statically link XNNPACK, protobuf, re2, cpuinfo, zlib and others; see NOTICE and the `licenses/` directory for the full list.

---

Ponko v1.3 —— 本地 AI App（Android）：聊天 + 绘图 + 打标

所有推理都在本机离线运行，不联网、无遥测，你的数据只留在设备本地。

### v1.3 亮点
- **图像打标（Tagger）**：把一张图反推成 Danbooru 风格标签，全程离线
  - 打标模型自备：从本机导入 WD14 类 ONNX 打标模型（`.onnx`）与其标签表（`selected_tags.csv`），不内置、不下载
  - 绘图参数区新增「**Tagger**」页：选图 → 设阈值 / 最多标签数 → 输出标签
  - 标签可一键**发送至文生图 / 图生图**（发送前弹确认框）
  - 结果页可把生成的图**发送至图生图**（当参考图）或**发送至 Tagger**（当输入图）
  - 走 ONNX Runtime，跟随模型页「运行方式」：CPU，或选 GPU 时用 NNAPI 加速
- **参数默认值**：三个参数页都加了「**设为默认值 / 恢复默认**」
  - 文生图、图生图**按 LoRA 开/关各存一套默认值**；切换 LoRA 开关会自动套用对应那套
- **模型导入**：绘图模型改为导入**单个 `.gguf` 文件**（不再整目录导入）；新增**本地 LoRA 导入**（`.safetensors` 单文件）
- 版本 1.2.0 -> 1.3.0（versionCode 5）

### 对话功能
- 双后端：LiteRT-LM（`.litertlm`）+ llama.cpp（`.gguf`）
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
- **图像打标（Tagger）**：离线 WD14 类 ONNX 打标，标签可发送至文生图 / 图生图

### 安装
- 仅支持 arm64-v8a（64 位 ARM 真机），minSdk 28（Android 9 及以上）
- v1.3（versionCode 5）可直接覆盖安装；更早的 debug 版或 v1.0 请先卸载（签名不同）
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
