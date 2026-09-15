# -*- coding: utf-8 -*-
"""一次性：设置 Ponko 仓库的英文描述 + Topics。"""
import json
import re
import urllib.request

TOK = re.search(r"ghp_\w+", open(r"C:\Users\Administrator\Desktop\git_repo_tok.txt",
                                 encoding="utf-8", errors="replace").read()).group(0)
REPO = "https://api.github.com/repos/visitor257/Ponko"

DESC = ("Fully offline AI app for Android: on-device chat (.litertlm / .gguf, with image "
        "input) + drawing (stable-diffusion.cpp GGUF) + image tagging (ONNX tagger). "
        "No network, no telemetry - everything stays on device.")

TOPICS = ["android", "kotlin", "llm", "llama-cpp", "litert-lm", "gguf",
          "multimodal", "vision-language-model", "stable-diffusion", "image-generation",
          "image-tagging", "onnxruntime", "on-device-ai", "offline"]


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
