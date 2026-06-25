$ErrorActionPreference = 'Stop'

function Add-RDPathPrefix {
  param([string]$PathPrefix)
  if ([string]::IsNullOrWhiteSpace($PathPrefix) -or -not (Test-Path -LiteralPath $PathPrefix)) {
    return
  }
  $parts = $env:PATH -split ';'
  if ($parts -notcontains $PathPrefix) {
    $env:PATH = "$PathPrefix;$env:PATH"
  }
}

function Get-RDToolchainsRoot {
  param([string]$Root)
  Join-Path $Root '.tmp\toolchains'
}

function Get-RDExistingPaths {
  param([string[]]$Candidates)
  $seen = @{}
  foreach ($candidate in $Candidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) {
      continue
    }
    try {
      if (Test-Path -LiteralPath $candidate) {
        $resolved = (Resolve-Path -LiteralPath $candidate).Path
        if (-not $seen.ContainsKey($resolved)) {
          $seen[$resolved] = $true
          $resolved
        }
      }
    } catch {
      continue
    }
  }
}

function Get-RDAndroidSdkCandidates {
  param([string]$Root)
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
  Get-RDExistingPaths $candidates.ToArray()
}

function Find-RDAdb {
  param([string]$Root)
  $adb = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
  if ($adb) {
    return $adb.Source
  }
  foreach ($sdkRoot in Get-RDAndroidSdkCandidates -Root $Root) {
    $candidate = Join-Path $sdkRoot 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) {
      return $candidate
    }
  }
  return $null
}

function Find-RDGo {
  param([string]$Root)
  $go = Get-Command 'go.exe' -ErrorAction SilentlyContinue
  if ($go) {
    return $go.Source
  }
  if (-not [string]::IsNullOrWhiteSpace($env:GOROOT)) {
    $candidate = Join-Path $env:GOROOT 'bin\go.exe'
    if (Test-Path -LiteralPath $candidate) {
      return $candidate
    }
  }
  $candidates = @(
    (Join-Path (Get-RDToolchainsRoot -Root $Root) 'go-1.26.3\go\bin\go.exe'),
    (Join-Path $env:TEMP 'codex-go-1.26.3\go\bin\go.exe'),
    'C:\Program Files\Go\bin\go.exe',
    'C:\Program Files (x86)\Go\bin\go.exe'
  )
  @(Get-RDExistingPaths $candidates) | Select-Object -First 1
}

function Find-RDJdkHome {
  param([string]$Root)
  if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $java) {
      return (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
    }
  }
  $javaCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
  if ($javaCommand) {
    $bin = Split-Path -Parent $javaCommand.Source
    return Split-Path -Parent $bin
  }

  $roots = @(
    (Join-Path (Get-RDToolchainsRoot -Root $Root) 'jdk-17'),
    (Join-Path $env:TEMP 'codex-jdk-17'),
    'C:\Program Files\Java',
    'C:\Program Files\Eclipse Adoptium'
  )
  foreach ($rootCandidate in Get-RDExistingPaths $roots) {
    $java = Get-ChildItem -LiteralPath $rootCandidate -Recurse -Filter 'java.exe' -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
      Select-Object -First 1
    if ($java) {
      return (Split-Path -Parent (Split-Path -Parent $java.FullName))
    }
  }
  return $null
}

function Find-RDGradle {
  param([string]$Root)
  $gradle = Get-Command 'gradle.bat' -ErrorAction SilentlyContinue
  if ($gradle) {
    return $gradle.Source
  }
  $wrapperVersion = Get-RDGradleWrapperVersion -Root $Root
  $candidates = @(
    (Join-Path (Get-RDToolchainsRoot -Root $Root) 'gradle-8.10.2\gradle-8.10.2\bin\gradle.bat'),
    (Join-Path $env:TEMP 'codex-gradle-8.10.2\gradle-8.10.2\bin\gradle.bat')
  )
  $found = @(Get-RDExistingPaths $candidates) | Select-Object -First 1
  if ($found) {
    return $found
  }
  $wrapperCache = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
  if (Test-Path -LiteralPath $wrapperCache) {
    $cachedGradles = @(Get-ChildItem -LiteralPath $wrapperCache -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue)
    if ($wrapperVersion) {
      $matched = $cachedGradles |
        Where-Object { $_.FullName -like "*\gradle-$wrapperVersion\bin\gradle.bat" } |
        Select-Object -First 1
      if ($matched) {
        return $matched.FullName
      }
    }
    $cached = $cachedGradles | Sort-Object FullName | Select-Object -First 1
    if ($cached) {
      return $cached.FullName
    }
  }
  return $null
}

function Get-RDGradleWrapperVersion {
  param([string]$Root)
  $propertiesPath = Join-Path $Root 'apps\android\gradle\wrapper\gradle-wrapper.properties'
  if (-not (Test-Path -LiteralPath $propertiesPath)) {
    return $null
  }
  $raw = Get-Content -LiteralPath $propertiesPath -Raw
  if ($raw -match 'gradle-([0-9][0-9A-Za-z.\-]*)-(?:bin|all)\.zip') {
    return $Matches[1]
  }
  return $null
}

function Initialize-RDToolchainEnvironment {
  param([string]$Root)

  $go = Find-RDGo -Root $Root
  if ($go) {
    $env:GOROOT = Split-Path -Parent (Split-Path -Parent $go)
    Add-RDPathPrefix (Split-Path -Parent $go)
  }

  $jdkHome = Find-RDJdkHome -Root $Root
  if ($jdkHome) {
    $env:JAVA_HOME = $jdkHome
    Add-RDPathPrefix (Join-Path $jdkHome 'bin')
  }

  $androidSdk = @(Get-RDAndroidSdkCandidates -Root $Root) | Select-Object -First 1
  if ($androidSdk) {
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
    Add-RDPathPrefix (Join-Path $androidSdk 'platform-tools')
  }

  $gradle = Find-RDGradle -Root $Root
  if ($gradle) {
    Add-RDPathPrefix (Split-Path -Parent $gradle)
  }

  [pscustomobject]@{
    go = if ($go) { [string]$go } else { $null }
    java_home = if ($jdkHome) { [string]$jdkHome } else { $null }
    gradle = if ($gradle) { [string]$gradle } else { $null }
    android_home = if ($androidSdk) { [string]$androidSdk } else { $null }
    adb = if (Find-RDAdb -Root $Root) { [string](Find-RDAdb -Root $Root) } else { $null }
  }
}

function Save-RDDownload {
  param(
    [string]$Url,
    [string]$OutFile
  )
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
  $oldProgress = $ProgressPreference
  $ProgressPreference = 'SilentlyContinue'
  try {
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
  } finally {
    $ProgressPreference = $oldProgress
  }
}

function Expand-RDArchiveIfMissing {
  param(
    [string]$Archive,
    [string]$Destination,
    [string]$Probe
  )
  if (Test-Path -LiteralPath $Probe) {
    return
  }
  New-Item -ItemType Directory -Force -Path $Destination | Out-Null
  Expand-Archive -LiteralPath $Archive -DestinationPath $Destination -Force
}

function Install-RDWindowsToolchains {
  param(
    [string]$Root,
    [switch]$SkipGo,
    [switch]$SkipJdk,
    [switch]$SkipGradle
  )

  $toolchains = Get-RDToolchainsRoot -Root $Root
  $downloads = Join-Path $toolchains 'downloads'
  New-Item -ItemType Directory -Force -Path $downloads | Out-Null

  if (-not $SkipGo -and -not (Find-RDGo -Root $Root)) {
    $archive = Join-Path $downloads 'go1.26.3.windows-amd64.zip'
    if (-not (Test-Path -LiteralPath $archive)) {
      Save-RDDownload 'https://go.dev/dl/go1.26.3.windows-amd64.zip' $archive
    }
    Expand-RDArchiveIfMissing $archive (Join-Path $toolchains 'go-1.26.3') (Join-Path $toolchains 'go-1.26.3\go\bin\go.exe')
  }

  if (-not $SkipJdk -and -not (Find-RDJdkHome -Root $Root)) {
    $archive = Join-Path $downloads 'temurin-jdk17-windows-x64.zip'
    if (-not (Test-Path -LiteralPath $archive)) {
      Save-RDDownload 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $archive
    }
    Expand-RDArchiveIfMissing $archive (Join-Path $toolchains 'jdk-17') (Join-Path $toolchains 'jdk-17')
  }

  $wantedGradleVersion = Get-RDGradleWrapperVersion -Root $Root
  if ([string]::IsNullOrWhiteSpace($wantedGradleVersion)) {
    $wantedGradleVersion = '8.10.2'
  }
  $wantedGradle = Join-Path (Get-RDToolchainsRoot -Root $Root) "gradle-$wantedGradleVersion\gradle-$wantedGradleVersion\bin\gradle.bat"
  if (-not $SkipGradle -and -not (Test-Path -LiteralPath $wantedGradle)) {
    $archive = Join-Path $downloads 'gradle-8.10.2-bin.zip'
    if (-not (Test-Path -LiteralPath $archive)) {
      Save-RDDownload 'https://services.gradle.org/distributions/gradle-8.10.2-bin.zip' $archive
    }
    Expand-RDArchiveIfMissing $archive (Join-Path $toolchains "gradle-$wantedGradleVersion") $wantedGradle
  }

  Initialize-RDToolchainEnvironment -Root $Root
}
