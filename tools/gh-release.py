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
TAG = "v1.2"
NAME = "Ponko v1.2"

BODY = """Ponko v1.2 — Fully offline AI app for Android: chat + drawing.

All inference runs on-device. No network, no telemetry; conversations stay on your phone.
See the [README](https://github.com/visitor257/Ponko#readme).

## v1.2 Highlights
- **Image-to-image**: the drawing page's parameters are split into two tabs — Text-to-Image / Image-to-Image
  - Image-to-Image: pick a reference photo from your gallery as the base, then use the prompt + "denoise strength" to restyle / change background / refine
  - Output size auto-aligns to the reference image (multiple of 64); still editable
  - Denoise strength 0.05–0.99: lower = closer to the source, higher = follows the prompt more
- Prompt placeholders are now example-style: positive "e.g. 1girl", negative "e.g. lowres, bad anatomy, ..."
- Version 1.1.1 -> 1.2.0 (versionCode 4)

## Chat
- Two backends: LiteRT-LM (`.litertlm`) + llama.cpp (`.gguf`)
- Multiple conversations, history persisted locally
- Reasoning and answer shown separately, collapsible; the thinking toggle works for both formats
- Streaming Markdown rendering (headings / lists / tables / code / links)
- Keep typing while generating; interrupt and continue; one-tap "regenerate" (random seed)
- Auto-follow scroll, pause on scroll-up, jump-to-bottom button

## Drawing
- Built-in custom stable-diffusion.cpp, loads SD1.5-family GGUF models, pure CPU
- **Text-to-image + image-to-image**
- LCM-LoRA acceleration: one-tap download in-app (Hugging Face official / hf-mirror), cuts 20 steps to 4–8
- Generate from chat: when both an LLM and an SD model are loaded, just say "draw a ..."
- Width / height / steps / CFG / seed / sampler / scheduler all adjustable

## Install
- arm64-v8a only (64-bit ARM devices), minSdk 28 (Android 9+)
- v1.2 (versionCode 4) installs over v1.1.1; uninstall older debug builds or v1.0 first (different signing key)
- Model files are not bundled: import chat models (`.litertlm` / `.gguf`) and a drawing model (SD1.5 GGUF) from the in-app "Models" page

## License
Code is MIT; art assets (icons, artwork) are all rights reserved. Third-party components (stable-diffusion.cpp, ggml, LiteRT-LM, llama.cpp, Markwon, etc.) are distributed under their respective licenses — see the repository NOTICE.

---

## 中文说明

Ponko v1.2 —— 本地 AI App（Android）：聊天 + 绘图

所有推理都在本机离线运行，不联网、无遥测，对话记录只保存在设备本地。

### v1.2 亮点
- **图生图（Image-to-Image）**：绘图页参数区拆成「文生图 / 图生图」两页
  - 图生图：从相册选一张参考图当底稿，配合提示词与「重绘强度」改风格 / 换背景 / 精修
  - 选图后自动把输出尺寸对齐参考图（64 的倍数），可手动调整
  - 重绘强度 0.05~0.99：越小越接近原图，越大越听提示词
- 提示词占位改为示例式（正面「例如：1girl」，负面「例如：lowres, bad anatomy, ...」）
- 版本 1.1.1 -> 1.2.0（versionCode 4）

### 对话功能
- 双后端：LiteRT-LM（`.litertlm`）+ llama.cpp（`.gguf`）
- 多对话管理，历史本地持久化
- 思考过程与正文分离、可折叠；思考开关对两种格式均生效
- Markdown 流式渲染（标题 / 列表 / 表格 / 代码块 / 链接）
- 生成中可继续打字；中断后可继续对话；一键「重新生成」（随机种子）
- 自动跟随滚动，上滑暂停、一键回到底部

### 绘图功能
- 内置自编 stable-diffusion.cpp，读 GGUF 格式的 SD1.5 系模型，纯 CPU 绘制
- **文生图 + 图生图**两种模式
- LCM-LoRA 加速：App 内一键下载（HF 官方 / hf-mirror 双源），20 步压到 4~8 步
- 对话页出图：语言模型与绘图模型同时加载时，直接说「画一张…」就会调用绘图模型
- 宽高 / 步数 / CFG / 种子 / 采样器 / 调度器全可调

### 安装
- 仅支持 arm64-v8a（64 位 ARM 真机），minSdk 28（Android 9 及以上）
- v1.2（versionCode 4）可直接覆盖安装；更早的 debug 版或 v1.0 请先卸载（签名不同）
- 模型文件需自备：对话模型（`.litertlm` / `.gguf`）与绘图模型（SD1.5 GGUF）分别在 App 内「模型」页导入

### 许可
代码 MIT；美术资源（图标、立绘）版权归作者所有。本项目包含的第三方组件（stable-diffusion.cpp、ggml、LiteRT-LM、llama.cpp、Markwon 等）按各自许可证分发，详见仓库 NOTICE。
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
