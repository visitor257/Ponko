# -*- coding: utf-8 -*-
"""在 GitHub 上为 visitor257/Ponko 创建 Release 并上传 APK 附件。"""
import json
import re
import sys
import urllib.request

REPO = "visitor257/Ponko"
TOKFILE = r"C:\Users\Administrator\Desktop\git_repo_tok.txt"
APK = r"C:\Users\Administrator\.qclaw\workspace-agent-e522fb09\LiteRT-Chat\app\build\outputs\apk\release\Ponko-release.apk"
TAG = "v1.1"
NAME = "Ponko v1.1"

BODY = """Ponko v1.1 —— 本地 AI App（Android）：聊天 + 绘图

所有推理都在本机离线运行，不联网、无遥测，对话记录只保存在设备本地。

## 本次新增（v1.0 → v1.1）
- **绘图功能**：内置自编 stable-diffusion.cpp，读 GGUF 格式的 SD1.5 系模型，纯 CPU 文生图
- **LCM-LoRA 加速**：App 内一键下载（HF 官方 / hf-mirror 双源），20 步压到 4~8 步
- **对话页出图**：语言模型与绘图模型同时加载时，直接说「画一张…」就会调用绘图模型
- **生成历史**：结果页保留本次运行最近 30 张，可翻看 / 单张删除（关掉 App 即清空）
- **参数记忆**：绘图参数（宽高 / 步数 / CFG / 种子 / 采样器 / 调度器 / LoRA）重启后保留
- **模型页改版**：分区卡片、双入口（对话模型 / 绘图模型）、加载卸载合一
- 界面文案精简，关于页新增「作者」「项目」

## 对话功能
- 双后端：LiteRT-LM（`.litertlm`）+ llama.cpp（`.gguf`）
- 多对话管理，历史本地持久化
- 思考过程与正文分离、可折叠；思考开关对两种格式均生效
- Markdown 流式渲染（标题 / 列表 / 表格 / 代码块 / 链接）
- 生成中可继续打字；中断后可继续对话；一键「重新生成」（随机种子，结果不重复）
- 自动跟随滚动，上滑暂停、一键回到底部

## 安装
- 仅支持 **arm64-v8a**（64 位 ARM 真机）
- **minSdk 28**（Android 9 及以上）
- 若装过旧版（debug 或 v1.0）请先卸载（签名不同，无法覆盖）
- 模型文件需自备：对话模型（`.litertlm` / `.gguf`）与绘图模型（SD1.5 GGUF）分别在 App 内「模型」页导入

## 许可
代码 MIT；美术资源（图标、立绘）版权归作者所有，详见仓库 NOTICE。
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
        "target_commitish": "main",
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
