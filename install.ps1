# 一键安装 MokrRun APK 到手机（处理 MIUI 的两层确认框）
param(
    [string]$Apk = "",
    [string]$Pkg = "com.mokrun.app"
)
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot "build\MokrRun.apk" }
$ErrorActionPreference = "Continue"
$adb = if ($env:ADB) { $env:ADB } else { "adb" }

function Dump($path) {
    & $adb shell "uiautomator dump $path" 2>&1 | Out-Null
    $tmp = "$env:TEMP\mokr_dump.xml"
    & $adb shell "cat $path" 2>&1 | Out-File -Encoding UTF8 $tmp
    & $adb shell "rm -f $path" 2>&1 | Out-Null
    return (Get-Content $tmp -Raw -Encoding UTF8)
}

function TapText($xml, $text) {
    $m = [regex]::Matches($xml, 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Count -eq 0) { return $false }
    $g = $m[0]
    $cx = [int](([int]$g.Groups[1].Value + [int]$g.Groups[3].Value) / 2)
    $cy = [int](([int]$g.Groups[2].Value + [int]$g.Groups[4].Value) / 2)
    Write-Host "  tap '$text' at $cx,$cy"
    & $adb shell "input tap $cx $cy" 2>&1 | Out-Null
    return $true
}

& $adb push $Apk /sdcard/mokr_install.apk 2>&1 | Out-Null
& $adb shell "am start -a android.intent.action.VIEW -d 'file:///sdcard/mokr_install.apk' -t application/vnd.android.package-archive" 2>&1 | Out-Null
Start-Sleep -Seconds 5

for ($i = 0; $i -lt 6; $i++) {
    $xml = Dump "/sdcard/_ui.xml"
    if ($xml -match "继续更新") { if (TapText $xml "继续更新") { Start-Sleep -Seconds 10; break } }
    elseif ($xml -match "继续安装") { if (TapText $xml "继续安装") { Start-Sleep -Seconds 10; break } }
    elseif ($xml -match "记住我的选择") {
        TapText $xml "记住我的选择" | Out-Null
        Start-Sleep -Milliseconds 500
        TapText $xml "允许" | Out-Null
        Start-Sleep -Seconds 4
    }
    elseif ($xml -match "允许") {
        TapText $xml "允许" | Out-Null
        Start-Sleep -Seconds 4
    }
    else {
        Start-Sleep -Seconds 3
    }
}

& $adb shell "appops set $Pkg SYSTEM_ALERT_WINDOW allow" 2>&1 | Out-Null
& $adb shell "pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS" 2>&1 | Out-Null
& $adb shell "pm grant $Pkg android.permission.ACCESS_FINE_LOCATION" 2>&1 | Out-Null
& $adb shell "pm grant $Pkg android.permission.ACCESS_COARSE_LOCATION" 2>&1 | Out-Null
Write-Host "granted: overlay + secure settings"

Start-Sleep -Seconds 3
$ver = (& $adb shell "dumpsys package $Pkg | grep -E 'versionName=' | head -1" 2>&1) -join ""
Write-Host "installed: $ver" -ForegroundColor Green
