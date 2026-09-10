<#
  用 keystore 重签 APK，固定 v2-only。

  为什么固定 v2-only：
    本项目 minSdk 28 / targetSdk 36。apksigner 在 minSdk >= 24 时不会生成 v1 签名
    （认为无意义），而一旦启用 v3，apksigner 又会把 v2 优化掉（v3 是 v2 超集），
    结果只剩 v3-only —— 而 v3 只有 Android 9+ 才能验证。
    实测 v2-only 是当前配置下兼容性最好的选择（Android 7.0+ 可验证）。

  用法：
    powershell -ExecutionPolicy Bypass -File sign-apk.ps1 -Apk <apk路径> [-Ks <jks>] [-Out <输出路径>]

  默认使用项目根目录的 ponko-release.jks（已在 .gitignore 中忽略，不会进仓库）。
#>
param(
  [Parameter(Mandatory=$true)][string]$Apk,
  [string]$Ks      = "ponko-release.jks",
  [string]$KsPass  = "ponkoRelease2026",
  [string]$Alias   = "ponko",
  [string]$KeyPass = "ponkoRelease2026",
  [string]$Out     = ""
)
$ErrorActionPreference = "Stop"
if (-not $Out) { $Out = $Apk }
if (-not (Test-Path -LiteralPath $Apk)) { throw "apk not found: $Apk" }
if (-not (Test-Path -LiteralPath $Ks))  { throw "keystore not found: $Ks" }

$bt = (Get-ChildItem "C:\Android\build-tools" -Directory |
       Sort-Object Name -Descending | Select-Object -First 1).FullName
$aligned = Join-Path $env:TEMP "ponko_aligned.apk"
$signed  = Join-Path $env:TEMP "ponko_signed.apk"

& "$bt\zipalign.exe" -p -f 4 $Apk $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& "$bt\apksigner.bat" sign --ks $Ks --ks-pass "pass:$KsPass" --ks-key-alias $Alias --key-pass "pass:$KeyPass" `
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false `
  --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Copy-Item -LiteralPath $signed -Destination $Out -Force
Write-Host "signed -> $Out"
& "$bt\apksigner.bat" verify --verbose $Out | Select-String "Verifies|scheme"
