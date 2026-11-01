#!/usr/bin/env python3
# Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
"""给 Ponko 的 APK 做 zipalign + 签名，产物只留一份。

用法:
    python tools/sign-apk.py                          # 用下面的默认路径
    python tools/sign-apk.py --apk A.apk --out B.apk
    python tools/sign-apk.py --keystore-dir C:/PonkoBuild

为什么要有这个脚本
==================
以前每次发版都在 shell 里临时写一段 Python 调 zipalign/apksigner，而那段代码
只 `os.remove` 了 *unsigned* 的输入，忘了删对齐和签名的中间产物。结果每签一次
就往 %TEMP% 里留两个 60~70 MB 的副本，文件名还是 `r_a` / `r_s` / `h_a` 这种
没有人能认出是什么的缩写。2026-11-02 一次清理，从 Temp 里扫出 **27 个、合计 1.7 GB**
这样的垃圾。

所以这个脚本把清理放进 `finally`：无论签名成功、失败还是抛异常，中间产物都删掉。
临时文件名也带上 pid，避免并发时互相覆盖。

签名方案
========
v1 + v2 + v3 全开，min-sdk 23。只开 v2 的话 Android 9 及以下装不上；只开 v1 的话
较新的系统会警告「该应用未针对最新版本优化」。
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_TOOLS = r"C:\Android\build-tools\36.0.0"
JDK = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

# 默认走构建沙箱：工程路径含中文时 AGP 拒绝构建，所以真正常用的产物在 C:\PonkoBuild
DEFAULT_APK = r"C:\PonkoBuild\app\build\outputs\apk\release\app-release-unsigned.apk"
DEFAULT_OUT = r"C:\PonkoBuild\Ponko-release.apk"


def read_keystore_props(kdir):
    """读 keystore.properties（已 gitignore，不进仓库）。"""
    path = os.path.join(kdir, "keystore.properties")
    if not os.path.exists(path):
        sys.exit("找不到 %s" % path)
    cfg = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            cfg[k.strip()] = v.strip()
    return cfg, path


def run(cmd, env, what):
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit("%s 失败:\n%s%s" % (what, r.stdout, r.stderr))
    return r


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def main():
    ap = argparse.ArgumentParser(description="zipalign + sign a Ponko APK")
    ap.add_argument("--apk", default=DEFAULT_APK, help="unsigned APK")
    ap.add_argument("--out", default=DEFAULT_OUT, help="signed output path")
    ap.add_argument("--keystore-dir", default=r"C:\PonkoBuild",
                    help="directory holding keystore.properties and the .jks")
    ap.add_argument("--keep-unsigned", action="store_true",
                    help="do not delete the unsigned input after signing")
    a = ap.parse_args()

    if not os.path.exists(a.apk):
        sys.exit("找不到输入 APK: %s" % a.apk)

    cfg, ks_props_path = read_keystore_props(a.keystore_dir)
    store_pass = cfg.get("storePassword", "")
    alias = cfg.get("keyAlias", "ponko")
    key_pass = cfg.get("keyPassword", store_pass)
    ks = os.path.join(a.keystore_dir, cfg.get("storeFile", "ponko-release.jks"))
    if not os.path.isabs(ks):
        ks = os.path.join(a.keystore_dir, ks)
    if not os.path.exists(ks):
        sys.exit("找不到 keystore: %s" % ks)

    env = dict(os.environ)
    env["JAVA_HOME"] = JDK
    env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")

    # 中间产物带 pid，且**一定**在 finally 里删掉 —— 这就是这个脚本存在的理由
    tag = "ponko-sign-%d" % os.getpid()
    aligned = os.path.join(tempfile.gettempdir(), tag + "-aligned.apk")
    signed = os.path.join(tempfile.gettempdir(), tag + "-signed.apk")

    try:
        run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-p", "-f", "4", a.apk, aligned],
            env, "zipalign")
        run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "sign",
             "--ks", ks,
             "--ks-pass", "pass:" + store_pass,
             "--ks-key-alias", alias,
             "--key-pass", "pass:" + key_pass,
             "--min-sdk-version", "23",
             "--v1-signing-enabled", "true",
             "--v2-signing-enabled", "true",
             "--v3-signing-enabled", "true",
             "--out", signed, aligned],
            env, "apksigner")

        if os.path.exists(a.out):
            os.remove(a.out)
        shutil.copyfile(signed, a.out)

        size = os.path.getsize(a.out)
        print("产物  : %s" % a.out)
        print("大小  : %.2f MB (%d B)" % (size / 1048576, size))
        print("sha256: %s" % sha256_of(a.out))

        # 证书指纹打出来：换签名会让老用户装不上，一眼能看出是不是同一把
        r = run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "verify",
                 "--print-certs", a.out], env, "apksigner verify")
        for line in r.stdout.splitlines():
            if "SHA-256 digest" in line:
                print("证书  : %s" % line.split(":", 1)[-1].strip())
                break
    finally:
        for p in (aligned, signed):
            if os.path.exists(p):
                try:
                    os.remove(p)
                except OSError:
                    pass
        if not a.keep_unsigned and os.path.exists(a.apk):
            try:
                os.remove(a.apk)
            except OSError:
                pass


if __name__ == "__main__":
    main()
