#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API 推送本地提交（git push 网络受限时的替代方案）

背景：某些网络环境下 github.com 的 git 端点（smart HTTP）连不上或极慢，
但 api.github.com 正常。此脚本用 Git Data API 完成等价推送：

  1. 取本地 HEAD 相对其父提交的变更文件清单
  2. 逐个创建 blob（只传变化的文件）
  3. 以远端最新提交的 tree 为 base 创建新 tree
  4. 创建 commit（沿用本地的作者/时间/提交信息）
  5. 更新远端分支引用

用法：
    python tools/push-via-api.py

环境变量：
    GH_TOKEN        GitHub token（未设置时从 GH_TOKEN_FILE 读取）
    GH_TOKEN_FILE   默认 ~/Desktop/git_repo_tok.txt
    GH_REPO         默认 visitor257/Ponko
    GH_BRANCH       默认 main
"""
import base64
import json
import os
import re
import shutil
import subprocess
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REPO = os.environ.get("GH_REPO", "visitor257/Ponko")
BRANCH = os.environ.get("GH_BRANCH", "main")
TOKEN_FILE = os.environ.get("GH_TOKEN_FILE",
                            os.path.join(os.path.expanduser("~"), "Desktop", "git_repo_tok.txt"))
if not os.environ.get("GH_TOKEN"):
    with open(TOKEN_FILE, encoding="utf-8") as fh:
        os.environ["GH_TOKEN"] = fh.read().split()[0].strip()
TK = os.environ["GH_TOKEN"]
GIT = shutil.which("git") or "git"
NEEDS_SHELL = GIT.lower().endswith((".cmd", ".bat"))


def api(method, path, data=None):
    url = "https://api.github.com" + path
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + TK)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "ponko-push")
    if body:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print("!! HTTP", e.code, e.read().decode()[:800])
        raise


def git(*args):
    cmd = '"' + GIT + '" ' + " ".join('"' + str(a) + '"' for a in args)
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", shell=NEEDS_SHELL)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


def ident(s):
    m = re.match(r"^(.*) <(.*)> (\d+) ([+-]\d{4})$", s)
    name, email, ts, tz = m.groups()
    off = timezone(timedelta(hours=int(tz[0:3]), minutes=int(tz[3:5])))
    return {"name": name, "email": email,
            "date": datetime.fromtimestamp(int(ts), tz=off).isoformat()}


def main():
    head = git("rev-parse", "HEAD").strip()
    raw = git("cat-file", "-p", "HEAD")
    pm = re.search(r"^parent ([0-9a-f]{40})$", raw, re.M)
    parent = pm.group(1) if pm else None
    author = re.search(r"^author (.+)$", raw, re.M).group(1)
    committer = re.search(r"^committer (.+)$", raw, re.M).group(1)
    message = raw.split("\n\n", 1)[1]
    print("本地 HEAD :", head)

    ref = api("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))
    remote_head = ref["object"]["sha"]
    print("远端 HEAD :", remote_head)
    if remote_head == head:
        print("已同步，无需推送。")
        return 0
    if remote_head != parent:
        print("注意：远端 HEAD 与本地父提交不同（%s vs %s），将基于远端最新状态提交。"
              % (remote_head[:7], (parent or "-")[:7]))
    base_tree = api("GET", "/repos/%s/git/commits/%s" % (REPO, remote_head))["tree"]["sha"]

    changed = [x for x in git("show", "--name-only", "--format=", head).split("\n") if x.strip()]
    print("变更文件 %d 个" % len(changed))

    entries = []
    for f in changed:
        p = os.path.join(ROOT, f.replace("/", os.sep))
        if not os.path.exists(p):
            entries.append({"path": f, "mode": "100644", "type": "blob", "sha": None})
            print("  删除", f)
            continue
        data = open(p, "rb").read()
        blob = api("POST", "/repos/%s/git/blobs" % REPO,
                   {"content": base64.b64encode(data).decode(), "encoding": "base64"})
        entries.append({"path": f, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  上传 %s (%dB)" % (f, len(data)))

    tree = api("POST", "/repos/%s/git/trees" % REPO, {"base_tree": base_tree, "tree": entries})
    commit = api("POST", "/repos/%s/git/commits" % REPO, {
        "message": message, "tree": tree["sha"], "parents": [remote_head],
        "author": ident(author), "committer": ident(committer),
    })
    api("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
        {"sha": commit["sha"], "force": False})
    print("远端分支已更新 ->", commit["sha"])
    print("（本地提交 %s 内容相同但 sha 不同，属正常：本地 git 与远端不再按 sha 对齐）" % head[:7])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
