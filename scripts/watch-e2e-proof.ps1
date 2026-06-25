param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [string]$ProofUrl = $env:RD_E2E_PROOF_URL,
  [int]$WaitSeconds = 600,
  [int]$IntervalSeconds = 2,
  [switch]$RequireAndroidDevice,
  [switch]$InstallAndroid,
  [switch]$LaunchAndroid,
  [switch]$SkipAdb,
  [switch]$SkipReset,
  [switch]$DryRun,
  [string]$AndroidApk = '',
  [string]$AndroidPackage = 'com.remotedesk.app',
  [string]$AndroidActivity = '.ui.MainActivity',
  [string]$AndroidTargetDeviceId = '',
  [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
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

function Use-LocalToolchains {
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

function Find-Adb {
  Find-RDAdb -Root $Root
}

function Find-Gradle {
  $gradle = Find-RDGradle -Root $Root
  if ($gradle) {
    return $gradle
  }
  throw 'gradle.bat was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install Gradle'
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

function Get-AdbDevices {
  param([string]$AdbPath)
  $lines = & $AdbPath devices
  if ($LASTEXITCODE -ne 0) {
    throw "adb devices exited $LASTEXITCODE"
  }
  $lines | ForEach-Object { Write-Host $_ }
  @($lines | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
}

function Resolve-AndroidApk {
  if (-not [string]::IsNullOrWhiteSpace($AndroidApk)) {
    if (-not (Test-Path -LiteralPath $AndroidApk)) {
      throw "Android APK not found: $AndroidApk"
    }
    return (Resolve-Path -LiteralPath $AndroidApk).Path
  }

  $defaultApk = Join-Path $Root 'apps\android\app\build\outputs\apk\debug\app-debug.apk'
  if (Test-Path -LiteralPath $defaultApk) {
    return (Resolve-Path -LiteralPath $defaultApk).Path
  }

  Write-Host 'Android debug APK was not found; building :app:assembleDebug first.'
  if (-not $toolchain.java_home) {
    throw 'Java 17 was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install JDK 17'
  }
  $gradle = Find-Gradle
  Invoke-External $gradle @(':app:assembleDebug') (Join-Path $Root 'apps\android')
  if (-not (Test-Path -LiteralPath $defaultApk)) {
    throw "Android debug APK still not found after build: $defaultApk"
  }
  return (Resolve-Path -LiteralPath $defaultApk).Path
}

function Resolve-AndroidComponent {
  if ($AndroidActivity.Contains('/')) {
    return $AndroidActivity
  }
  if ($AndroidActivity.StartsWith('.')) {
    return "$AndroidPackage/$AndroidActivity"
  }
  return "$AndroidPackage/$AndroidActivity"
}

function Resolve-AndroidWsUrl {
  param([string]$Url)
  $uri = [System.Uri]$Url
  $builder = [System.UriBuilder]::new($uri)
  switch ($uri.Scheme.ToLowerInvariant()) {
    'http' { $builder.Scheme = 'ws' }
    'https' { $builder.Scheme = 'wss' }
    'ws' { $builder.Scheme = 'ws' }
    'wss' { $builder.Scheme = 'wss' }
    default { throw "unsupported proof URL scheme for Android launch: $($uri.Scheme)" }
  }
  $path = $uri.AbsolutePath
  switch -Regex ($path) {
    '^/$' { $builder.Path = '/ws'; break }
    '/e2e-proof$' { $builder.Path = ($path -replace '/e2e-proof$', '/ws'); break }
    '/ws$' { $builder.Path = $path; break }
    default { $builder.Path = ($path.TrimEnd('/') + '/ws') }
  }
  $builder.Query = ''
  $builder.Fragment = ''
  $builder.Uri.AbsoluteUri
}

function Get-ProofSnapshot {
  param([string]$Url)
  Invoke-RestMethod -Method Get -Uri $Url
}

function Save-ProofSnapshot {
  param(
    [string]$Name,
    [object]$Snapshot
  )
  if (-not $script:ProofRunDir) {
    return
  }
  $path = Join-Path $script:ProofRunDir $Name
  $Snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding UTF8
  Write-Host "Saved proof snapshot: $path"
}

function Save-LatestProofSnapshot {
  param([string]$Name)
  try {
    $snapshot = Get-ProofSnapshot $ProofUrl
    Save-ProofSnapshot $Name $snapshot
  } catch {
    Write-Warning "Could not save proof snapshot ${Name}: $($_.Exception.Message)"
  }
}

$toolchain = Initialize-RDToolchainEnvironment -Root $Root

if ([string]::IsNullOrWhiteSpace($ProofUrl)) {
  $ProofUrl = 'http://localhost:18081/e2e-proof'
}
if ($WaitSeconds -le 0) {
  throw 'WaitSeconds must be greater than zero'
}
if ($IntervalSeconds -le 0) {
  throw 'IntervalSeconds must be greater than zero'
}

$script:ProofRunDir = $null
if ([string]::IsNullOrWhiteSpace($OutDir)) {
  $OutDir = Join-Path $Root '.tmp\e2e-proof-runs'
}
if (-not $DryRun) {
  $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $script:ProofRunDir = Join-Path $OutDir $timestamp
  New-Item -ItemType Directory -Force -Path $script:ProofRunDir | Out-Null
}

Write-Host "E2E proof URL: $ProofUrl"
Write-Host "Wait: ${WaitSeconds}s, interval: ${IntervalSeconds}s"
if ($script:ProofRunDir) {
  Write-Host "Proof evidence directory: $script:ProofRunDir"
}

$adb = $null
if (-not $SkipAdb) {
  $adb = Find-Adb
  if (-not $adb) {
    if ($RequireAndroidDevice -or $InstallAndroid -or $LaunchAndroid) {
      throw 'adb.exe was not found'
    }
    Write-Warning 'adb.exe was not found; Android -> Windows proof cannot be executed from this machine yet.'
  } else {
    Write-Host "ADB: $adb"
    $devices = @(Get-AdbDevices $adb)
    if ($devices.Count -eq 0) {
      if ($RequireAndroidDevice -or $InstallAndroid -or $LaunchAndroid) {
        throw 'no Android device is visible to adb'
      }
      Write-Warning 'No Android device is visible to adb. Connect a device before running Android -> Windows proof.'
    } else {
      Write-Host "Visible Android devices: $($devices.Count)"
    }
  }
}

if ($DryRun) {
  Write-Host 'Dry run complete. No proof reset or wait was performed.'
  return
}

if ($InstallAndroid) {
  if (-not $adb) {
    throw 'InstallAndroid requires adb'
  }
  $apk = Resolve-AndroidApk
  Write-Host "Installing Android APK: $apk"
  Invoke-External $adb @('install', '-r', $apk)
}

if ($LaunchAndroid) {
  if (-not $adb) {
    throw 'LaunchAndroid requires adb'
  }
  $component = Resolve-AndroidComponent
  $androidWsUrl = Resolve-AndroidWsUrl $ProofUrl
  $launchArgs = @(
    'shell',
    'am',
    'start',
    '-n',
    $component,
    '-e',
    'rd_ws_url',
    $androidWsUrl,
    '--ez',
    'rd_auto_connect',
    'true'
  )
  if (-not [string]::IsNullOrWhiteSpace($AndroidTargetDeviceId)) {
    $launchArgs += @('-e', 'rd_target_device_id', $AndroidTargetDeviceId.Trim())
  }
  Write-Host "Launching Android activity: $component"
  Write-Host "Injecting Android relay URL: $androidWsUrl"
  if (-not [string]::IsNullOrWhiteSpace($AndroidTargetDeviceId)) {
    Write-Host "Injecting Android target device ID: $($AndroidTargetDeviceId.Trim())"
  }
  Invoke-External $adb $launchArgs
}

if (-not $SkipReset) {
  Write-Host ''
  Write-Host '==> Resetting relay proof state'
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
  Save-LatestProofSnapshot 'reset.json'
} else {
  Save-LatestProofSnapshot 'initial.json'
}

Write-Host ''
Write-Host 'Run the real routes now, then leave this watcher running:'
Write-Host '  1. Android -> Windows: Android controller selects Windows target, render first frame, press E2E proof input.'
Write-Host '  2. Windows -> Windows: Windows desktop controller selects Windows target, render first frame, press E2E proof input.'
Write-Host '  3. Windows -> macOS: Windows desktop controller selects macOS target, render first frame, press E2E proof input.'
Write-Host 'macOS target must have Screen Recording and Accessibility permissions before route 3.'
Write-Host ''
Write-Host '==> Waiting for /e2e-proof complete=true'
if (-not $toolchain.go) {
  throw 'go.exe was not found; run .\scripts\bootstrap-windows-toolchains.ps1 or install Go 1.26.x'
}
try {
  Invoke-External $toolchain.go @(
    'run',
    './cmd/e2e-proof-check',
    '-url',
    $ProofUrl,
    '-wait',
    "${WaitSeconds}s",
    '-interval',
    "${IntervalSeconds}s"
  ) (Join-Path $Root 'apps\server')
  Save-LatestProofSnapshot 'final.json'
} catch {
  Save-LatestProofSnapshot 'latest-on-failure.json'
  throw
}

Write-Host 'E2E proof complete for Android -> Windows, Windows -> Windows, and Windows -> macOS.'
