#requires -Version 5.1
<#
User-driven, read-only Android evidence capture. Does not install, launch, grant
permissions, clear logs, stop apps, or modify device data. Keep the app foreground.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedVersionName,
    [Parameter(Mandatory = $true)][ValidateRange(1, 2147483647)][int]$ExpectedVersionCode,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]*$')][string]$Serial,
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedApkSha256,
    [ValidatePattern('^[a-fA-F0-9]{40}$')][string]$ExpectedCommit,
    [ValidatePattern('^[A-Za-z0-9_-]{1,48}$')][string]$CaseId = 'manual-session',
    [ValidateRange(100, 2000)][int]$LogLines = 600,
    [string]$AdbPath = 'adb',
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$package = 'com.im6705.ardogy'
$adb = (Get-Command $AdbPath -CommandType Application -ErrorAction Stop).Source
$apk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'ApkPath must identify an APK file.' }
$apkSha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ExpectedApkSha256 -and $apkSha -ne $ExpectedApkSha256.ToLowerInvariant()) {
    throw 'Local APK SHA256 does not match ExpectedApkSha256. Nothing was captured.'
}

$deviceLines = @(& $adb devices 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed. Check Android platform-tools and USB debugging.' }
$devices = @($deviceLines | ForEach-Object {
    if ("$_" -match '^(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader)(?:\s|$)') {
        [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
    }
})
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw 'Provide -Serial explicitly unless exactly one device is connected.' }
    $Serial = $devices[0].Serial
}
if ($Serial -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]*$') { throw 'Unsupported device serial format.' }
$selected = @($devices | Where-Object { $_.Serial -ceq $Serial })
if ($selected.Count -ne 1 -or $selected[0].State -ne 'device') {
    throw 'Selected device is not connected and authorized. No evidence was captured.'
}

function Invoke-DeviceAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $lines = @(& $adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb operation failed: $($Arguments[0]). Check the connected device." }
    return ($lines -join "`n")
}

function Assert-AppForeground {
    # Inspect in memory only; never persist the list of other activities/apps.
    $activities = Invoke-DeviceAdb -Arguments @('shell', 'dumpsys', 'activity', 'activities')
    $foregroundPackages = @([regex]::Matches($activities,
        '(?m)^\s*(?:topResumedActivity|mResumedActivity)\s*[:=][^\r\n]*?\s([A-Za-z0-9_.]+)/') |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($foregroundPackages.Count -ne 1 -or $foregroundPackages[0] -cne $package) {
        throw 'Target app is not the sole resumed foreground app. Return to AR Dogy full-screen and retry. Screenshot refused.'
    }
    # Refuse captures while keyguard or a permission/notification/system overlay has focus.
    $windows = Invoke-DeviceAdb -Arguments @('shell', 'dumpsys', 'window', 'windows')
    $focused = @([regex]::Matches($windows,
        '(?m)^\s*mCurrentFocus=[^\r\n]*?\s([A-Za-z0-9_.]+)/') |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($focused.Count -ne 1 -or $focused[0] -cne $package) {
        throw 'Target app does not own the focused window. Close system dialogs/overlays and retry. Screenshot refused.'
    }
}

$packageDump = Invoke-DeviceAdb -Arguments @('shell', 'dumpsys', 'package', $package)
$versionCodeMatch = [regex]::Match($packageDump, '(?m)^\s*versionCode=(\d+)')
$versionNameMatch = [regex]::Match($packageDump, '(?m)^\s*versionName=([^\r\n]+)')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw 'Expected package is not installed, or its version could not be read.'
}
$actualVersionName = $versionNameMatch.Groups[1].Value.Trim()
$actualVersionCode = [int]$versionCodeMatch.Groups[1].Value
if ($actualVersionName -cne $ExpectedVersionName -or $actualVersionCode -ne $ExpectedVersionCode) {
    throw "Installed version mismatch: found $actualVersionName ($actualVersionCode). Nothing was captured."
}
$packagePaths = @( (Invoke-DeviceAdb -Arguments @('shell', 'pm', 'path', $package)) -split '\r?\n' |
    Where-Object { $_ -like 'package:*' } | ForEach-Object { $_.Substring(8).Trim() })
if ($packagePaths.Count -ne 1) { throw 'Expected one installed release APK. Split installs are outside this capture contract.' }
$installedPath = $packagePaths[0]
if ($installedPath -notmatch '^/data/app/[-A-Za-z0-9_./+=~]+\.apk$') { throw 'Unexpected installed APK path; hash check refused.' }
$installedHashOutput = Invoke-DeviceAdb -Arguments @('shell', 'sha256sum', $installedPath)
$installedHashMatch = [regex]::Match($installedHashOutput, '^([a-fA-F0-9]{64})\s')
if (-not $installedHashMatch.Success) { throw 'Could not hash installed APK. Update platform-tools/device shell; no identity claim can be made.' }
$installedSha = $installedHashMatch.Groups[1].Value.ToLowerInvariant()
if ($installedSha -ne $apkSha) { throw 'Installed APK differs from the supplied APK. Nothing was captured.' }

$appPid = (Invoke-DeviceAdb -Arguments @('shell', 'pidof', $package)).Trim()
if ($appPid -notmatch '^\d+$') { throw 'Expected exactly one running app process. Open AR Dogy and retry.' }
Assert-AppForeground

$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'work/device-evidence' }
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$captureDirectory = Join-Path $outputRoot "$stamp-$CaseId-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
[void](New-Item -ItemType Directory -Path $captureDirectory)
$screenshotPending = Join-Path $captureDirectory 'screenshot.pending.png'
$screenshot = Join-Path $captureDirectory 'screenshot.png'

try {
    Assert-AppForeground
    # Use a binary stream, avoiding PNG corruption in Windows PowerShell's redirection.
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adb
    $start.Arguments = "-s $Serial exec-out screencap -p"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($start)
    $errorRead = $process.StandardError.ReadToEndAsync()
    $file = [IO.File]::Open($screenshotPending, [IO.FileMode]::CreateNew)
    try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $process.WaitForExit()
    [void]$errorRead.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw 'Screenshot capture failed.' }
    $process.Dispose()
    Assert-AppForeground
    $header = [IO.File]::ReadAllBytes($screenshotPending)
    if ($header.Length -lt 8 -or [BitConverter]::ToString($header[0..7]) -ne '89-50-4E-47-0D-0A-1A-0A') {
        throw 'Device did not return a valid PNG screenshot.'
    }
    Move-Item -LiteralPath $screenshotPending -Destination $screenshot
} catch {
    # Only this newly created, exact temporary file can be removed. Never touch device data.
    if (Test-Path -LiteralPath $screenshotPending -PathType Leaf) { Remove-Item -LiteralPath $screenshotPending }
    throw
}

$appLog = Invoke-DeviceAdb -Arguments @('logcat', '-d', '-t', "$LogLines", '-v', 'threadtime', "--pid=$appPid")
$systemLogRaw = Invoke-DeviceAdb -Arguments @('logcat', '-d', '-t', "$LogLines", '-v', 'threadtime', '-s',
    'ActivityTaskManager:I', 'ActivityManager:I', 'AndroidRuntime:E', 'ARCore:W')
# Persist only system lines explicitly naming the target; omit unrelated apps and stack traces.
$systemLog = @($systemLogRaw -split '\r?\n' | Where-Object { $_ -match [regex]::Escape($package) }) -join "`n"
Set-Content -LiteralPath (Join-Path $captureDirectory 'logcat-app.txt') -Value $appLog -Encoding UTF8
Set-Content -LiteralPath (Join-Path $captureDirectory 'logcat-system-target.txt') -Value $systemLog -Encoding UTF8

function Get-DeviceProperty([string]$Name) { (Invoke-DeviceAdb -Arguments @('shell', 'getprop', $Name)).Trim() }
$serialHashAlgorithm = [Security.Cryptography.SHA256]::Create()
try { $serialHash = [BitConverter]::ToString($serialHashAlgorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Serial))).Replace('-', '').ToLowerInvariant() }
finally { $serialHashAlgorithm.Dispose() }
$metadata = [ordered]@{
    schemaVersion = 1
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    caseId = $CaseId
    package = $package
    versionName = $actualVersionName
    versionCode = $actualVersionCode
    suppliedApkFileName = [IO.Path]::GetFileName($apk)
    suppliedApkSha256 = $apkSha
    installedApkSha256 = $installedSha
    installedApkMatchesSuppliedApk = $true
    expectedApkSha256 = $ExpectedApkSha256
    expectedCommit = $ExpectedCommit
    commitVerification = 'User-supplied release reference only; not extracted or verified from APK. Compare release build-info.json independently.'
    deviceSerialSha256 = $serialHash
    manufacturer = Get-DeviceProperty 'ro.product.manufacturer'
    model = Get-DeviceProperty 'ro.product.model'
    androidVersion = Get-DeviceProperty 'ro.build.version.release'
    sdk = Get-DeviceProperty 'ro.build.version.sdk'
    securityPatch = Get-DeviceProperty 'ro.build.version.security_patch'
    appPid = [int]$appPid
    maxLogLinesPerQuery = $LogLines
    screenshotForegroundCheckedBeforeAndAfter = $true
    acceptanceResult = 'NOT_RUN'
    acceptanceNote = 'Capture success is not a functional test pass. Fill acceptance-results.csv after manual observation.'
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $captureDirectory 'metadata.json') -Encoding UTF8
$caseNames = @('INSTALL', 'CAMERA_DENY', 'CAMERA_GRANT', 'CAMERA_PERMANENT_DENY', 'AR_SERVICE',
    'UNSUPPORTED_DEVICE', 'FLOOR_PLACE', 'WALK_IDLE', 'PAUSE_RESUME', 'RESET', 'TRACKING_LOSS',
    'BACKGROUND_RESUME', 'PLANE_MERGE', 'PLANE_EDGE')
$caseNames | ForEach-Object {
    [pscustomobject]@{ caseId = $_; result = 'NOT_RUN'; observedAtUtc = ''; observation = ''; evidenceFile = '' }
} | Export-Csv -LiteralPath (Join-Path $captureDirectory 'acceptance-results.csv') -NoTypeInformation -Encoding UTF8
Write-Host "Evidence saved: $captureDirectory"
Write-Host 'Review screenshots/logs before sharing. All acceptance cases remain NOT_RUN until you record the observations.'

