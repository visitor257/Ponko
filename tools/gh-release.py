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
APK = r"C:\Users\Administrator\WorkBuddy\智能Agent\LiteRT-Chat\app\build\outputs\apk\release\Ponko-release.apk"
TAG = "v1.7.1"
NAME = "Ponko v1.7.1"

BODY = """Ponko v1.7.1 - Offline-capable AI app for Android: chat (image, file and document input) + drawing + tagging, each of which can be served over its own OpenAI-compatible API.

All inference runs on-device: apart from the optional in-app LoRA download, no feature needs the network. No telemetry; your data stays on your phone.
See the [README](https://github.com/visitor257/Ponko#readme).

## v1.7.1 Highlights
- **Drawing API**: the loaded drawing model can now be served over an OpenAI-compatible image endpoint - `POST /v1/images/generations` (returns `b64_json`), plus `GET /v1/models` and `GET /health`. Send `{"prompt": "..."}` and the phone renders it; `n` (1-4), `size`, `seed`, `steps`, `cfg_scale` and `negative_prompt` are all optional, and anything you omit falls back to the drawing page's current settings
- **The chat API and the drawing API are two independent services**: separate ports (8080 / 8081), separate API keys, separate on/off switches and separate request counters, so you can expose one without the other. They share a single foreground service and one notification, whose text lists every address that is actually listening. Changing a port or key restarts that service for you. Off by default: one image pins the CPU for tens of seconds, so the drawing endpoint is not reachable from the network unless you turn it on
- **Fixes**
  - The About page showed a stale version number ("Version 1.7.0" while running 1.7.1). It is now read from the installed package, so it can never go out of sync again
  - The **Settings / About** sections forgot whether you had collapsed them; the state is now remembered across restarts
  - Starting an API service no longer requires a chat model to be loaded first (it never actually did - only the endpoints need models, and they answer 503 with an explanation until one is loaded)
- Version 1.7.0 -> 1.7.1 (versionCode 14)

## Chat
- Two backends: LiteRT-LM (`.litertlm`) + llama.cpp (`.gguf`)
- **Image input**: `.litertlm` multimodal models, or `.gguf` vision models with a matching mmproj
- **File input**: plain text / Markdown / JSON / CSV / logs / source code, up to 2 per message
- **Document input**: `.docx` / `.pptx` / `.xlsx` / `.odt` / `.ods` / `.odp` / `.epub` / `.rtf` / `.html` / `.pdf`, extracted on-device
- **Tagger as a chat tool**: a loaded tagger can be called from chat when the chat model cannot see images itself
- Multiple conversations, history persisted locally
- Reasoning and answer shown separately, collapsible; the thinking toggle works for both formats
- Streaming Markdown rendering (headings / lists / tables / code / links)
- Keep typing while generating; interrupt and continue; one-tap "regenerate" (keeps images and files)
- Auto-follow scroll, pause on scroll-up, jump-to-bottom button

## API (served from the phone)
Two independent services - start either, or both, from the Models/API page:

| | Chat API | Drawing API |
|---|---|---|
| Endpoints | `POST /v1/chat/completions` (SSE streaming or plain), `GET /v1/models`, `GET /health` | `POST /v1/images/generations` (`b64_json`), `GET /v1/models`, `GET /health` |
| Default port | 8080 | 8081 |
| API key | optional, its own | optional, its own |
| Switch | its own | its own (off by default) |

- Runs as a foreground service (type `specialUse`, avoiding the 6-hour limit Android 15+ puts on `dataSync`) with a notification and a WifiLock, so it keeps serving in the background and with the screen off
- One request at a time (a local model cannot run concurrently): a busy server answers 429
- Context reuse: clients resend the whole history each turn, but the server recognises "same history + new question" and continues the existing session instead of recomputing from scratch
- Security: the servers listen on **every** network interface of the phone, including public ones. Without an API key anyone who can reach the address can use your model and battery - set a key, or keep it on a trusted network

## UI
- **Dark mode**: a three-position slider in Settings (Light / Follow system / Dark), persisted and applied without restarting
- Settings is split into collapsible **Settings / About** groups, and their collapsed state is remembered
- The Models page is **Models/API**; the Chat model and Draw model pages each have **Model / API** sub-tabs with the full set of API settings

## Drawing
- Built-in custom stable-diffusion.cpp; loads GGUF drawing models - single-file all-in-one (SD1.5 / SD2 / SDXL) or a multi-file (split) set filled in slot by slot; CPU by default, optional GPU (Vulkan) acceleration with automatic CPU fallback
- GPU status: only drawing (Vulkan), tagging (NNAPI) and `.litertlm` chat carry GPU code; all build-time-verified only, gguf chat is CPU-only
- Text-to-image + image-to-image (denoise strength 0.05-0.99, output size auto-aligned to the reference)
- **Samplers**: the list matches sd.cpp 1:1 (incl. DPM++ 2M SDE / SDE B&T / LMS / Res Multistep / ER SDE / Euler GE)
- **Live progress preview**: the Result pane has a "Result / Process" switch; the Process page refreshes a step-by-step low-res preview (built-in latent projection, no extra model) with the same phase / step / elapsed text as the Parameters page
- Per-page parameter defaults with confirmation (separate sets for LoRA on/off)
- LCM-LoRA acceleration: one-tap download in-app (Hugging Face official / hf-mirror), cuts 20 steps to 4-8; local LoRA import supported
- Generate from chat: when both an LLM and an SD model are loaded, just say "draw a ..."
- **Image tagging (Tagger)**: on-device ONNX tagger (WD14-style), tags can be forwarded to T2I / I2I

## Install
- arm64-v8a only (64-bit ARM devices), minSdk 28 (Android 9+)
- v1.7.1 (versionCode 14) installs over v1.7.0 / v1.6.2 / v1.6.1 / v1.6.0 / v1.5.0 / v1.4.1 / v1.4.0 / v1.3.x / v1.2; uninstall older debug builds or v1.0 first (different signing key)
- Model files are not bundled: import chat models (`.litertlm` / `.gguf`), a drawing model (GGUF: single file, or a multi-file set) and, optionally, a tagger (`.onnx` + `.csv`) from the in-app Models/API page

## License
Code is MIT; art assets (icons, artwork) are all rights reserved. Third-party components (stable-diffusion.cpp, ggml, Vulkan-Hpp/Vulkan-Headers, LiteRT-LM, llama.cpp, Markwon, PDFBox-Android, ONNX Runtime, etc.) are distributed under their respective licenses - see the repository NOTICE. ONNX Runtime bundles extra third-party components; their notices are included under `licenses/`. The native runtimes also statically link XNNPACK, protobuf, re2, cpuinfo, zlib and others; see NOTICE and the `licenses/` directory for the full list.

---

Ponko v1.7.1 —— 本地 AI App（Android）：聊天（可发图、可发文件与文档）+ 绘图 + 打标，三者都能各自开放成 OpenAI 兼容接口

所有推理都在本机完成：除了「手动下载 LoRA」，其余功能都不需要联网；无遥测，数据只留在设备本地。

### v1.7.1 亮点
- **绘图 API**：已加载的绘图模型现在也能开放成 OpenAI 兼容的图像生成接口——`POST /v1/images/generations`（返回 `b64_json`），另有 `GET /v1/models` 与 `GET /health`。传一句 `{"prompt": "..."}` 手机就出图；`n`（1~4）、`size`、`seed`、`steps`、`cfg_scale`、`negative_prompt` 都可选，没传的沿用绘图页当前设置
- **对话 API 与绘图 API 是两套独立服务**：各自的端口（8080 / 8081）、各自的 API key、各自的开关与请求计数，可以只开放其中一个。两者**共用一个前台服务与一条通知**，通知里会列出真正在监听的地址；端口或 key 改动会自动重启对应的服务。绘图接口**默认关闭**——出一张图要吃满 CPU 几十秒，不开就不对局域网开放
- **修复**
  - 「关于」页显示的版本号是写死的，升级到 1.7.1 后仍显示 1.7.0。现改为从安装包读取，以后不可能再对不上
  - 「设置 / 关于」两个分区的**折叠状态没有被记住**，合上后重启又会展开；现在会记住
  - 启动 API 服务不再要求「先加载一个对话模型」（本来就不需要——只有各接口需要模型，未加载时接口会返回 503 并说明原因）
- 版本 1.7.0 -> 1.7.1（versionCode 14）

### 对话功能
- 双后端：LiteRT-LM（`.litertlm`）+ llama.cpp（`.gguf`）
- **图片输入**：`.litertlm` 多模态模型，或配了 mmproj 的 `.gguf` 视觉模型
- **文件输入**：纯文本 / Markdown / JSON / CSV / 日志 / 代码等，一条最多 2 个
- **文档输入**：`.docx` / `.pptx` / `.xlsx` / `.odt` / `.ods` / `.odp` / `.epub` / `.rtf` / `.html` / `.pdf`，本机抽取正文
- **打标当工具用**：对话模型看不见图片时，可在聊天里调用已加载的打标模型
- 多对话管理，历史本地持久化
- 思考过程与正文分离、可折叠；思考开关对两种格式均生效
- Markdown 流式渲染（标题 / 列表 / 表格 / 代码块 / 链接）
- 生成中可继续打字；中断后可继续对话；一键「重新生成」（会带上图片与文件）
- 自动跟随滚动，上滑暂停、一键回到底部

### API（把手机当服务器）
两套独立服务，在「模型/API」页各自启停：

| | 对话 API | 绘图 API |
|---|---|---|
| 接口 | `POST /v1/chat/completions`（SSE 流式 / 非流式）、`GET /v1/models`、`GET /health` | `POST /v1/images/generations`（`b64_json`）、`GET /v1/models`、`GET /health` |
| 默认端口 | 8080 | 8081 |
| API key | 可选，各自一套 | 可选，各自一套 |
| 开关 | 独立 | 独立（默认关闭） |

- 以前台服务运行（`specialUse` 类型，避开 Android 15+ 对 dataSync 的 6 小时限制）+ 常驻通知 + WifiLock，App 退到后台、锁屏都继续服务
- **一次一个请求**：本地模型跑不了并发，忙时返回 429
- **上下文续跑**：客户端每轮重发整段历史，服务端能识别「同一段历史 + 新问题」并复用已有会话，不从头重算
- **安全提示**：两个服务都监听本机**所有**网络接口（含公网地址）。不设 key 时，任何能连到该地址的人都能调用你的模型和电量——建议设 key，或只在可信网络里用

### 界面
- **深色模式**：设置页三档滑块（亮 / 跟随系统 / 暗），重启后记住，切换就地生效不重启
- 设置页分成「设置 / 关于」两个可折叠分区，**折叠状态会被记住**
- 「模型」页更名「模型/API」；对话模型与绘图模型页内各有「模型 / API」两个子页，API 那一侧的设置两边完全一致

### 绘图功能
- 内置自编 stable-diffusion.cpp；读 GGUF 格式的绘图模型——单文件整合版（SD1.5 / SD2 / SDXL）或按槽位补齐的多文件（拆包）组合；默认 CPU，可选 GPU（Vulkan）加速，失败自动回退 CPU
- GPU 现状：只有绘图（Vulkan）、打标（NNAPI）、对话 `.litertlm` 三条带 GPU 代码，且都仅构建侧验证；对话 GGUF 只能 CPU
- 文生图 + 图生图（重绘强度 0.05~0.99，尺寸自动对齐参考图）
- **采样器**：列表与 sd.cpp 一一对应（含 DPM++ 2M SDE / SDE B&T / LMS / Res Multistep / ER SDE / Euler GE）
- **过程实时预览**：「结果」面板可切「结果 / 过程」子页，过程页逐步刷新低分辨率预览（内置 latent 投影，无需额外模型），进度文案与参数页一致
- 每个参数页可「设为默认值 / 恢复默认」（带确认），文生图 / 图生图按 LoRA 开 / 关各存一套
- LCM-LoRA 加速：App 内一键下载（HF 官方 / hf-mirror 双源），20 步压到 4~8 步；也支持导入本地 LoRA
- 对话页出图：语言模型与绘图模型同时加载时，直接说「画一张…」就会调用绘图模型
- **图像打标（Tagger）**：本机离线 ONNX 打标（WD14 系），标签可发送至文生图 / 图生图

### 安装
- 仅支持 arm64-v8a（64 位 ARM 真机），minSdk 28（Android 9 及以上）
- v1.7.1（versionCode 14）可直接覆盖安装 v1.7.0 / v1.6.2 / v1.6.1 / v1.6.0 / v1.5.0 / v1.4.1 / v1.4.0 / v1.3.x / v1.2；更早的 debug 版或 v1.0 请先卸载（签名不同）
- 模型文件需自备：对话模型（`.litertlm` / `.gguf`）、绘图模型（GGUF：单文件或多文件组合），打标模型（`.onnx` + `.csv`）可选，都在 App 内「模型/API」页导入

### 许可
代码 MIT；美术资源（图标、立绘）版权归作者所有。本项目包含的第三方组件（stable-diffusion.cpp、ggml、Vulkan-Hpp/Vulkan-Headers、LiteRT-LM、llama.cpp、Markwon、PDFBox-Android、ONNX Runtime 等）按各自许可证分发，详见仓库 NOTICE。ONNX Runtime 另自带若干第三方组件，其声明收录在 `licenses/` 目录。各运行时原生库还静态链入 XNNPACK、protobuf、re2、cpuinfo、zlib 等，完整清单见 NOTICE 与 `licenses/` 目录。
"""


def token():
    txt = open(TOKFILE, encoding="utf-8", errors="replace").read()
    # 只认 token 本体；别用「删非字母数字」的清洗法，会把第二行的 ddl 到期日拼进来
    m = re.search(r"gh[pousr]_[A-Za-z0-9]+", txt)
    if not m:
        raise SystemExit("在 %s 里没找到 GitHub token（应以 gh?_ 开头）" % TOKFILE)
    return m.group(0)


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
