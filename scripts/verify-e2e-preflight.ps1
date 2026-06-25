param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [string]$ProofUrl = '',
  [switch]$RequireAndroidDevice,
  [switch]$SkipServer,
  [switch]$SkipDesktop,
  [switch]$SkipAndroid,
  [switch]$SkipDiffCheck,
  [switch]$SkipAdb
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]
. (Join-Path $PSScriptRoot 'rd-toolchain.ps1')

function Add-PathPrefix {
  param([string]$PathPrefix)
  if ([string]::IsNullOrWhiteSpace($PathPrefix) -or -not (Test-Path -LiteralPath $PathPrefix)) {
    return
  }
  $parts = $env:PATH -split ';'
  if ($parts -notcontains $PathPrefix) {
    $env:PATH = "$PathPrefix;$env:PATH"
  }
}

function Get-AndroidSdkCandidates {
  $candidates = New-Object System.Collections.Generic.List[string]
  foreach ($value in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      $candidates.Add($value) | Out-Null
    }
  }
  if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Out-Null
  }
  $candidates.Add((Join-Path $env:TEMP 'codex-android-sdk')) | Out-Null

  $seen = @{}
  foreach ($candidate in $candidates) {
    $resolved = $null
    try {
      if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
        $resolved = (Resolve-Path -LiteralPath $candidate).Path
      }
    } catch {
      $resolved = $null
    }
    if ($resolved -and -not $seen.ContainsKey($resolved)) {
      $seen[$resolved] = $true
      $resolved
    }
  }
}

function Use-BundledToolchains {
  $goRoot = Join-Path $env:TEMP 'codex-go-1.26.3\go'
  if (Test-Path -LiteralPath $goRoot) {
    $env:GOROOT = $goRoot
    Add-PathPrefix (Join-Path $goRoot 'bin')
  }

  $jdkRoot = Join-Path $env:TEMP 'codex-jdk-17\jdk-17.0.19+10'
  if (Test-Path -LiteralPath $jdkRoot) {
    $env:JAVA_HOME = $jdkRoot
    Add-PathPrefix (Join-Path $jdkRoot 'bin')
  }

  $androidSdk = @(Get-AndroidSdkCandidates) | Select-Object -First 1
  if ($androidSdk) {
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
    Add-PathPrefix (Join-Path $androidSdk 'platform-tools')
  }
}

function Invoke-External {
  param(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$WorkingDirectory = $Root
  )

  Push-Location $WorkingDirectory
  try {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
      throw "$FilePath $($Arguments -join ' ') exited $LASTEXITCODE"
    }
  } finally {
    Pop-Location
  }
}

function Invoke-Step {
  param(
    [string]$Name,
    [scriptblock]$Body
  )

  Write-Host ""
  Write-Host "==> $Name"
  try {
    & $Body
    Write-Host "PASS: $Name"
  } catch {
    $message = $_.Exception.Message
    $failures.Add("${Name}: ${message}") | Out-Null
    Write-Host "FAIL: $Name"
    Write-Host $message
  }
}

function Find-Gradle {
  $gradle = Find-RDGradle -Root $Root
  if ($gradle) {
    return $gradle
  }
  throw 'gradle.bat was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install Gradle'
}

function Find-Adb {
  Find-RDAdb -Root $Root
}

$toolchain = Initialize-RDToolchainEnvironment -Root $Root

Invoke-Step 'Client E2E proof schema gate' {
  Invoke-External 'powershell' @(
    '-NoProfile',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    (Join-Path $Root 'scripts\check-e2e-proof-clients.ps1')
  )
}

if (-not $SkipServer) {
  Invoke-Step 'Server tests' {
    if (-not $toolchain.go) {
      throw 'go.exe was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install Go 1.26.x'
    }
    Invoke-External $toolchain.go @('test', './...') (Join-Path $Root 'apps\server')
  }
}

if ($ProofUrl.Trim() -ne '') {
  Invoke-Step 'Relay proof reset check' {
    if (-not $toolchain.go) {
      throw 'go.exe was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install Go 1.26.x'
    }
    Invoke-External $toolchain.go @(
      'run',
      './cmd/e2e-proof-check',
      '-url',
      $ProofUrl,
      '-reset-only'
    ) (Join-Path $Root 'apps\server')
  }
}

if (-not $SkipDesktop) {
  Invoke-Step 'Desktop web build' {
    Invoke-External 'npm.cmd' @('run', 'build') (Join-Path $Root 'apps\desktop')
  }

  Invoke-Step 'Desktop native tests' {
    Invoke-External 'cargo' @('test') (Join-Path $Root 'apps\desktop\src-tauri')
  }
}

if (-not $SkipAndroid) {
  Invoke-Step 'Android debug build' {
    if (-not $toolchain.java_home) {
      throw 'Java 17 was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install JDK 17'
    }
    $gradle = Find-Gradle
    Invoke-External $gradle @(':app:assembleDebug') (Join-Path $Root 'apps\android')
  }
}

if (-not $SkipDiffCheck) {
  Invoke-Step 'Git diff whitespace check' {
    Invoke-External 'git' @('diff', '--check') $Root
  }
}

if (-not $SkipAdb) {
  Invoke-Step 'ADB device visibility' {
    $adb = Find-Adb
    if (-not $adb) {
      if ($RequireAndroidDevice) {
        throw 'adb.exe was not found'
      }
      Write-Warning 'adb.exe was not found; Android real-device E2E proof cannot run yet.'
      return
    }

    $lines = & $adb devices
    if ($LASTEXITCODE -ne 0) {
      throw "adb devices exited $LASTEXITCODE"
    }
    $lines | ForEach-Object { Write-Host $_ }
    $devices = @($lines | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
    if ($devices.Count -eq 0) {
      if ($RequireAndroidDevice) {
        throw 'no Android device is visible to adb'
      }
      Write-Warning 'No Android device is visible to adb. This is the remaining blocker for Android -> Windows proof.'
    } else {
      Write-Host "Visible Android devices: $($devices.Count)"
    }
  }
}

Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host 'E2E preflight failed:'
  $failures | ForEach-Object { Write-Host " - $_" }
  exit 1
}

Write-Host 'E2E preflight passed. Real-device route completion still requires /e2e-proof complete=true.'
