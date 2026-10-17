# -*- coding: utf-8 -*-
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
"""一次性：设置 Ponko 仓库的英文描述 + Topics。"""
import json
import os
import re
import urllib.request

# token 不写死本机目录：走环境变量或家目录（expanduser 不暴露用户名）
TOKFILE = (os.environ.get("PONKO_GH_TOKEN_FILE")
           or os.path.join(os.path.expanduser("~"), "Desktop", "git_repo_tok.txt"))
TOK = re.search(r"gh[pousr]_\w+",
                open(TOKFILE, encoding="utf-8", errors="replace").read()).group(0)
REPO = "https://api.github.com/repos/visitor257/Ponko"

DESC = ("Offline-capable AI app for Android: on-device chat (.litertlm / .gguf) with image and "
        "file input, drawing (stable-diffusion.cpp GGUF, optional Vulkan GPU) and image tagging "
        "(ONNX tagger). Optional GPU acceleration; all inference runs locally; no telemetry.")

TOPICS = ["android", "kotlin", "llm", "llama-cpp", "litert-lm", "gguf",
          "multimodal", "vision-language-model", "stable-diffusion", "image-generation",
          "image-tagging", "onnxruntime", "on-device-ai", "offline", "vulkan"]


def req(url, data=None, ctype=None, method=None):
    h = {"Authorization": "token " + TOK,
         "User-Agent": "ponko-meta",
         "Accept": "application/vnd.github+json"}
    if ctype:
        h["Content-Type"] = ctype
    return urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h, method=method), timeout=60)


d = json.loads(req(REPO, data=json.dumps({"description": DESC}).encode("utf-8"),
                   ctype="application/json", method="PATCH").read())
print("DESC ->", d["description"])

d2 = json.loads(req(REPO + "/topics", data=json.dumps({"names": TOPICS}).encode("utf-8"),
                    ctype="application/json", method="PUT").read())
print("TOPICS ->", d2["names"])
