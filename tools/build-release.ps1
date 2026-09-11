# 构建 Ponko release APK 并交付（Z 盘 + 网盘）
#
# 为什么需要这个脚本：手工把 assembleRelease / 签名 / 拷贝 / 上传串成一行时，
# 一旦编译失败，后面的步骤照样会用【上一次的旧 APK】继续跑，容易把旧包当新包发出去。
# 所以这里每一步都检查退出码，失败立刻退出。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
#   powershell -ExecutionPolicy Bypass -File tools\build-release.ps1 -SkipUpload   # 只出包不传网盘

param(
    [string]$RemoteName = "Ponko-release.apk",
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $PSScriptRoot        # 项目根（tools 的上一级）
$root = Split-Path -Parent $proj                # workspace 根（存放 upload-to-drive.ps1）

$gradle = "C:\gradle\gradle-9.7.1\bin\gradle.bat"
$unsigned = Join-Path $proj "app\build\outputs\apk\release\app-release-unsigned.apk"
$signed = Join-Path $proj "app\build\outputs\apk\release\Ponko-release.apk"

function Step($name) { Write-Host "`n=== $name ===" -ForegroundColor Cyan }

Step "1/5 assembleRelease"
& $gradle -p $proj assembleRelease 2>&1 | Select-String -Pattern "BUILD|^e: |error:|FAILED"
if ($LASTEXITCODE -ne 0) { throw "gradle 构建失败（exit=$LASTEXITCODE），已停止，不交付任何产物" }
if (-not (Test-Path $unsigned)) { throw "未找到构建产物：$unsigned" }

Step "2/5 签名（ponko-release.jks，v2-only）"
& powershell -ExecutionPolicy Bypass -File (Join-Path $proj "tools\sign-apk.ps1") -Apk $unsigned -Ks "ponko-release.jks"
if ($LASTEXITCODE -ne 0) { throw "签名失败，已停止" }

Step "3/5 生成交付文件"
Copy-Item $unsigned $signed -Force
$f = Get-Item $signed
Write-Host ("  {0}  {1} bytes  {2}" -f $f.Name, $f.Length, $f.LastWriteTime)

Step "4/5 拷贝到 Z 盘（VirtualBox 共享盘，可能离线）"
try {
    Copy-Item $signed "Z:\$RemoteName" -Force
    Write-Host ("  Z:\{0} OK" -f $RemoteName)
} catch {
    Write-Warning ("  Z 盘不可用，跳过：{0}" -f $_.Exception.Message)
}

if ($SkipUpload) {
    Write-Host "`n-SkipUpload 指定，跳过网盘上传。" -ForegroundColor Yellow
    return
}

Step "5/5 上传网盘「临时」"
$up = Join-Path $root "upload-to-drive.ps1"
if (-not (Test-Path $up)) { throw "找不到上传脚本：$up" }
& powershell -ExecutionPolicy Bypass -File $up -Path $signed -RemoteName $RemoteName -Force
if ($LASTEXITCODE -ne 0) { throw "网盘上传失败" }

Write-Host "`n全部完成。" -ForegroundColor Green
