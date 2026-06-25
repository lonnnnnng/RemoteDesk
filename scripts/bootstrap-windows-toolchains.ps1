param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [switch]$SkipGo,
  [switch]$SkipJdk,
  [switch]$SkipGradle
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rd-toolchain.ps1')

$result = Install-RDWindowsToolchains -Root $Root -SkipGo:$SkipGo -SkipJdk:$SkipJdk -SkipGradle:$SkipGradle
$result | ConvertTo-Json -Depth 3
