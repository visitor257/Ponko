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

# Vulkan 后端的 host 工具（vulkan-shaders-gen）在 Windows 上需要 MSVC 的 cl.exe。
# Gradle daemon 会继承【启动时】的环境，所以这里先把 MSVC 环境导进来并重启 daemon，
# 否则 CMake 在 daemon 里 configure 时找不到 host 编译器（Host compiler not found）。
Step "0/5 载入 MSVC 环境"
$vcvars = $null
$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
    $vsPath = (& $vswhere -latest -products * -property installationPath) 2>$null | Select-Object -First 1
    if ($vsPath) {
        $cand = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
        if (Test-Path $cand) { $vcvars = $cand }
    }
}
if (-not $vcvars) {
    foreach ($p in @(
        (Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"),
        (Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"),
        (Join-Path ${env:ProgramFiles} "Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat")
    )) { if (Test-Path $p) { $vcvars = $p; break } }
}
if ($vcvars) {
    cmd /c "`"$vcvars`" >nul 2>&1 && set" | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "env:$($matches[1])" -Value $matches[2] -ErrorAction SilentlyContinue }
    }
    Write-Host "  MSVC: $vcvars"
    & $gradle --stop | Out-Null   # 让下一次构建用带 MSVC 环境的新 daemon
} else {
    Write-Warning "  未找到 vcvars64.bat：SD_VULKAN=ON 的构建会失败（Host compiler not found）"
}

Step "1/5 assembleRelease"
& $gradle -p $proj assembleRelease 2>&1 | Select-String -Pattern "BUILD|^e: |error:|FAILED"
if ($LASTEXITCODE -ne 0) { throw "gradle 构建失败（exit=$LASTEXITCODE），已停止，不交付任何产物" }
if (-not (Test-Path $unsigned)) { throw "未找到构建产物：$unsigned" }

Step "2/5 签名（ponko-release.jks，v2-only）"
$ks = Join-Path $proj "ponko-release.jks"
if (-not (Test-Path $ks)) { throw "找不到签名密钥：$ks" }
Push-Location $proj
try {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $proj "tools\sign-apk.ps1") -Apk $unsigned -Ks $ks
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) { throw "签名失败，已停止" }

Step "3/5 生成交付文件"
Copy-Item $unsigned $signed -Force
$f = Get-Item $signed
Write-Host ("  {0}  {1} bytes  {2}" -f $f.Name, $f.Length, $f.LastWriteTime)

Step "4/5 拷贝到 Z 盘（VirtualBox 共享盘，可能离线）"
# 注意：共享盘离线时 Copy-Item 会长时间阻塞且不抛异常，try/catch 拦不住，
# 必须放进后台作业加超时，否则整个构建流程会卡在这里。
$zj = Start-Job -ScriptBlock {
    param($src, $dst)
    try { Copy-Item $src $dst -Force; "OK" } catch { "FAIL: $($_.Exception.Message)" }
} -ArgumentList $signed, "Z:\$RemoteName"
if (Wait-Job $zj -Timeout 15) {
    Write-Host ("  Z:\{0} -> {1}" -f $RemoteName, (Receive-Job $zj))
} else {
    Write-Warning "  Z 盘 15 秒内无响应（共享盘离线？），跳过"
    Stop-Job $zj
}
Remove-Job $zj -Force

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
