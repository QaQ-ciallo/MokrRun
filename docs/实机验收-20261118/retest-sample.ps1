param(
    [int]$Minutes = 16,
    [int]$IntervalSec = 30,
    [string]$Adb = "C:\Program Files\ASUS\GlideX\adb.exe",
    [string]$Dir = ""
)
$ErrorActionPreference = "Continue"
New-Item -ItemType Directory -Force -Path "$Dir\shots-retest" | Out-Null
$out = Join-Path $Dir "retest-samples.txt"
$deadline = (Get-Date).AddMinutes($Minutes)
$i = 0
while ((Get-Date) -lt $deadline) {
    $i++
    $ts = Get-Date -Format "HH:mm:ss"
    # 排除我自己 shell 命令的回显（adbd 行），只取模块真实输出
    $tick  = & $Adb shell "logcat -d -v time | grep -E 'tick dist=' | grep -v 'in ShellService' | tail -1" 2>&1
    $state = & $Adb shell "logcat -d -v time | grep -E 'MokrRun: (idle mode=|command mode=|applied from intent|config via|step listener|face check|face freeze)' | grep -v 'in ShellService' | tail -3" 2>&1
    $top   = & $Adb shell "dumpsys activity activities | grep -E 'topResumedActivity' | head -1" 2>&1
    & $Adb shell "screencap -p /sdcard/r$i.png" 2>&1 | Out-Null
    & $Adb pull "/sdcard/r$i.png" "$Dir\shots-retest\r$i.png" 2>&1 | Out-Null
    & $Adb shell "rm -f /sdcard/r$i.png" 2>&1 | Out-Null
    Add-Content -Path $out -Encoding UTF8 -Value ("[{0}] retest#{1}" -f $ts, $i)
    Add-Content -Path $out -Encoding UTF8 -Value ("  top: " + ($top -join " ").Trim())
    Add-Content -Path $out -Encoding UTF8 -Value ("  tick: " + ($tick -join " ").Trim())
    foreach ($l in $state) { if ($l) { Add-Content -Path $out -Encoding UTF8 -Value ("  evt: " + $l.Trim()) } }
    Add-Content -Path $out -Encoding UTF8 -Value "---"
    Start-Sleep -Seconds $IntervalSec
}
Add-Content -Path $out -Encoding UTF8 -Value ("[{0}] retest sampling finished" -f (Get-Date -Format "HH:mm:ss"))