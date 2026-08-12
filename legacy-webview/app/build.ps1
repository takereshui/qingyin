$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Out = Join-Path (Split-Path $Root -Parent) "MolanLightMusic.apk"
$GoApk = Join-Path $env:USERPROFILE "go\bin\goapk.exe"
if (-not (Test-Path $GoApk)) { $GoApk = "goapk" }

& $GoApk build `
  -s $Root `
  --package com.molan.lightmusic `
  --name "轻音" `
  --version-name "1.0.0" `
  --version-code 1 `
  --icon (Join-Path $Root "icons\icon-512.png") `
  --min-sdk 24 `
  --target-sdk 35 `
  $Out

if (Test-Path $Out) {
  Write-Host "OK: $Out ($([math]::Round((Get-Item $Out).Length/1KB, 1)) KB)"
} else {
  Write-Error "APK not found"
}