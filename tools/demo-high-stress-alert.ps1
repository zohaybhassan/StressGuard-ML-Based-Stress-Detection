[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$packageName = 'com.example.stressguard'
$actionName = 'com.example.stressguard.debug.SHOW_HIGH_STRESS_ALERT'

function Find-Adb {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    $localProperties = Join-Path $PSScriptRoot '..\local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
            $candidate = Join-Path $sdkPath 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }

    throw 'ADB was not found. Open Android Studio once or add platform-tools to PATH.'
}

function Run-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = & $script:adbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: adb $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Get-ConnectedDevices {
    $lines = Run-Adb -Arguments @('devices', '-l')
    return @(
        $lines |
            Where-Object { $_ -match '^([^\s]+)\s+device(?:\s|$)' } |
            ForEach-Object { [regex]::Match($_, '^([^\s]+)').Groups[1].Value }
    )
}

function Test-PackageInstalled {
    param([Parameter(Mandatory)][string]$Serial)
    $path = Run-Adb -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $packageName)
    return ($path -join '') -match '^package:'
}

Write-Host ''
Write-Host 'StressGuard live-demo alert' -ForegroundColor Cyan
Write-Host 'Finding the connected phone and Wear OS watch...'

$script:adbPath = Find-Adb
$devices = Get-ConnectedDevices
if ($devices.Count -lt 2) {
    throw "Two ADB devices are required, but only $($devices.Count) were found. Connect the phone and pair/connect the watch, then try again."
}

$watchSerial = $null
$phoneSerial = $null
foreach ($serial in $devices) {
    $characteristics = (
        Run-Adb -Arguments @('-s', $serial, 'shell', 'getprop', 'ro.build.characteristics')
    ) -join ''
    if ($characteristics -match 'watch') {
        if (-not $watchSerial) { $watchSerial = $serial }
    } elseif (-not $phoneSerial) {
        $phoneSerial = $serial
    }
}

if (-not $phoneSerial -or -not $watchSerial) {
    throw 'Could not identify one phone and one Wear OS watch. Check `adb devices -l` and reconnect both devices.'
}

if (-not (Test-PackageInstalled -Serial $phoneSerial)) {
    throw 'StressGuard is not installed on the connected phone. Install the app debug APK first.'
}
if (-not (Test-PackageInstalled -Serial $watchSerial)) {
    throw 'StressGuard is not installed on the connected watch. Install the Wear debug APK first.'
}

Write-Host "Phone: $phoneSerial" -ForegroundColor DarkGray
Write-Host "Watch: $watchSerial" -ForegroundColor DarkGray

foreach ($serial in @($phoneSerial, $watchSerial)) {
    # The permission may already be granted. The command is deliberately best-effort so an older
    # Android version does not prevent the demo alert from being sent.
    & $adbPath -s $serial shell pm grant $packageName android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
    & $adbPath -s $serial shell input keyevent 224 2>$null | Out-Null
}

$phoneResult = Run-Adb -Arguments @(
    '-s', $phoneSerial,
    'shell', 'am', 'broadcast',
    '-a', $actionName,
    '-n', "$packageName/.DebugStressAlertReceiver"
)
$watchResult = Run-Adb -Arguments @(
    '-s', $watchSerial,
    'shell', 'am', 'broadcast',
    '-a', $actionName,
    '-n', "$packageName/.presentation.DebugStressAlertReceiver"
)

if (($phoneResult -join "`n") -notmatch 'Broadcast completed' -or
    ($watchResult -join "`n") -notmatch 'Broadcast completed') {
    throw 'One of the demo broadcasts was not accepted. Reinstall both debug APKs and try again.'
}

Write-Host ''
Write-Host 'High-stress demo alerts sent successfully.' -ForegroundColor Green
Write-Host 'Phone: TEST - Sustained high stress'
Write-Host 'Watch: High stress detected - Stress 92%'
Write-Host ''
Write-Host 'These demo alerts are not saved to Trends, feedback, history, or Supabase.' -ForegroundColor DarkGray
