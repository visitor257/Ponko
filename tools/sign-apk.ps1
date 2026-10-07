<#
  用 keystore 重签 APK，固定 v1 + v2 + v3 三方案全开。

  为什么三方案全开：
    apksigner 按「minSdkVersion」决定生成哪些方案：
      - minSdk >= 24 时不生成 v1（JAR 签名）
      - minSdk >= 28 且开了 v3 时，会把 v2 优化掉 —— 只剩 v3-only
    本项目 minSdk = 28，所以实测结果：
      - 只开 v2          -> 只有 v2-only（部分国产 ROM 安装器 / 平台校验会判「签名校验失败」）
      - 开 v1+v2+v3      -> 被优化成 v3-only（比 v2-only 更差：Android 9 以下无法验证）
      - 再加 --min-sdk-version 23（只影响签名方案选择，不改 APK 真实 minSdk）
                        -> v1 + v2 + v3 全带上，兼容性最好
    校验：`apksigner verify --verbose --min-sdk-version 23 <apk>` 三项应均为 true。

  口令不写在脚本里（历史上写死过，且随仓库公开了）：
    优先读项目根目录的 keystore.properties（已被 .gitignore 忽略），字段：
      storeFile / storePassword / keyAlias / keyPassword
    其次读环境变量 PONKO_KS_PASS / PONKO_KEY_PASS。

  用法：
    powershell -ExecutionPolicy Bypass -File sign-apk.ps1 -Apk <apk路径> [-Out <输出路径>] [-Ks <jks>]

  默认使用项目根目录的 ponko-release.jks（已被 .gitignore 忽略，不会进仓库）。
#>
param(
  [Parameter(Mandatory=$true)][string]$Apk,
  [string]$Ks      = "",
  [string]$KsPass  = "",
  [string]$Alias   = "",
  [string]$KeyPass = "",
  [string]$Out     = ""
)
$ErrorActionPreference = "Stop"
if (-not $Out) { $Out = $Apk }

# 项目根 = 本脚本所在 tools 目录的上一级
$projRoot = Split-Path -Parent $PSScriptRoot
$props    = Join-Path $projRoot "keystore.properties"

$cfg = @{}
if (Test-Path -LiteralPath $props) {
  Get-Content -LiteralPath $props | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $i = $line.IndexOf("=")
    if ($i -gt 0) { $cfg[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim() }
  }
}

if (-not $Ks) {
  if ($cfg['storeFile']) { $Ks = Join-Path $projRoot $cfg['storeFile'] }
  else                   { $Ks = Join-Path $projRoot "ponko-release.jks" }
}
if (-not $KsPass)  { if ($cfg['storePassword']) { $KsPass = $cfg['storePassword'] } else { $KsPass = $env:PONKO_KS_PASS } }
if (-not $Alias)   { if ($cfg['keyAlias'])      { $Alias  = $cfg['keyAlias']      } else { $Alias  = "ponko" } }
if (-not $KeyPass) { if ($cfg['keyPassword'])   { $KeyPass = $cfg['keyPassword']  } else { $KeyPass = $env:PONKO_KEY_PASS } }
if (-not $KeyPass) { $KeyPass = $KsPass }

if (-not (Test-Path -LiteralPath $Apk)) { throw "apk not found: $Apk" }
if (-not (Test-Path -LiteralPath $Ks))  { throw "keystore not found: $Ks (可创建 $props 或设置 PONKO_KS_PASS)" }
if (-not $KsPass)                       { throw "keystore 口令为空：请在 $props 填 storePassword，或设置 PONKO_KS_PASS" }

$bt = (Get-ChildItem "C:\Android\build-tools" -Directory |
       Sort-Object Name -Descending | Select-Object -First 1).FullName
$aligned = Join-Path $env:TEMP "ponko_aligned.apk"
$signed  = Join-Path $env:TEMP "ponko_signed.apk"

& "$bt\zipalign.exe" -p -f 4 $Apk $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& "$bt\apksigner.bat" sign --ks $Ks --ks-pass "pass:$KsPass" --ks-key-alias $Alias --key-pass "pass:$KeyPass" `
  --min-sdk-version 23 `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Copy-Item -LiteralPath $signed -Destination $Out -Force
Write-Host "signed -> $Out"
& "$bt\apksigner.bat" verify --verbose --min-sdk-version 23 $Out | Select-String "Verifies|scheme"
