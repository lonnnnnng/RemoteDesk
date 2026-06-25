param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

$desktopPath = Join-Path $Root 'apps\desktop\src\main.jsx'
$androidPath = Join-Path $Root 'apps\android\app\src\main\java\com\remotedesk\app\ui\MainActivity.kt'
$androidControllerPath = Join-Path $Root 'apps\android\app\src\main\java\com\remotedesk\app\controller\StubSessionController.kt'

$desktop = Get-Content -LiteralPath $desktopPath -Raw
$android = Get-Content -LiteralPath $androidPath -Raw
$androidController = Get-Content -LiteralPath $androidControllerPath -Raw
$androidCombined = $android + "`n" + $androidController

$checks = @(
  @{ Name = 'desktop session.metrics.report send'; File = $desktopPath; Text = $desktop; Patterns = @('session.metrics.report', 'first_frame_ms', 'rendered_frames') },
  @{ Name = 'desktop input coverage fields'; File = $desktopPath; Text = $desktop; Patterns = @('remote_input_applied_click', 'remote_input_applied_drag', 'remote_input_applied_keyboard', 'remote_input_applied_wheel', 'remote_input_required_coverage_complete', 'remote_input_applied_categories') },
  @{ Name = 'desktop proof input sequence categories'; File = $desktopPath; Text = $desktop; Patterns = @('sendE2EProofInputSequence', 'options.inputCategory || "click"', 'inputCategory: "drag"', 'input_category: "keyboard"', 'input_category: "wheel"') },
  @{ Name = 'desktop proof API controls'; File = $desktopPath; Text = $desktop; Patterns = @('deriveE2EProofUrl', 'refreshE2EProofSnapshot', 'resetE2EProofSnapshot', 'method: "DELETE"') },
  @{ Name = 'android session.metrics.report send'; File = "$androidPath;$androidControllerPath"; Text = $androidCombined; Patterns = @('sessionMetricsReportMessage', '"first_frame_ms"', '"rendered_frames"', 'maybeSendLiveE2EProofReport') },
  @{ Name = 'android input coverage fields'; File = $androidPath; Text = $android; Patterns = @('"remote_input_applied_click"', '"remote_input_applied_drag"', '"remote_input_applied_keyboard"', '"remote_input_applied_wheel"', '"remote_input_required_coverage_complete"', '"remote_input_applied_categories"') },
  @{ Name = 'android proof input sequence categories'; File = "$androidPath;$androidControllerPath"; Text = $androidCombined; Patterns = @('sendE2EProofInputSequence', '"input_category" to inputCategory', 'inputCategory = "drag"', '"input_category" to "keyboard"', '"input_category" to "wheel"') },
  @{ Name = 'android proof API controls'; File = $androidPath; Text = $android; Patterns = @('buildE2EProofUrl', 'refreshE2EProofSnapshot', 'debugProofRefreshButton', 'debugProofResetButton') },
  @{ Name = 'android adb launch relay extras'; File = $androidPath; Text = $android; Patterns = @('EXTRA_WS_URL', 'EXTRA_TARGET_DEVICE_ID', 'EXTRA_AUTO_CONNECT', 'getStringExtra(EXTRA_WS_URL)', 'getStringExtra(EXTRA_TARGET_DEVICE_ID)', 'getBooleanExtra(EXTRA_AUTO_CONNECT') }
)

$failures = @()
foreach ($check in $checks) {
  foreach ($pattern in $check.Patterns) {
    if (-not $check.Text.Contains($pattern)) {
      $failures += [pscustomobject]@{
        Check = $check.Name
        File = $check.File
        Missing = $pattern
      }
    }
  }
}

if ($failures.Count -gt 0) {
  $failures | Format-Table -AutoSize | Out-String | Write-Error
  exit 1
}

[pscustomobject]@{
  ok = $true
  checked_files = @($desktopPath, $androidPath, $androidControllerPath)
  checked_groups = $checks.Count
} | ConvertTo-Json -Depth 3
