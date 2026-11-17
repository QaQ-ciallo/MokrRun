# On-site sampling (read-only): screenshot + engine log tail every N seconds.
# Usage: powershell -NoProfile -File onsite-sample.ps1 -Minutes 14 -IntervalSec 60
param(
    [int]$Minutes = 10,
    [int]$IntervalSec = 60,
    [string]$Adb = "C:\Program Files\ASUS\GlideX\adb.exe",
    [string]$Dir = "C:\Users\Hen\Desktop\鎵嬫満\MokrRun\docs\瀹炴満楠屾敹-20261118"
)
$ErrorActionPreference = "Continue"
New-Item -ItemType Directory -Force -Path "$Dir\shots" | Out-Null
$samples = Join-Path $Dir "samples-run2.txt"
$deadline = (Get-Date).AddMinutes($Minutes)
$i = 0
while ((Get-Date) -lt $deadline) {
    $i++
    $ts = Get-Date -Format "HH:mm:ss"
    $tick = & $Adb shell "logcat -d -v time | grep -E 'MokrRun|MokrRunProbe|MokrRunCfg' | tail -12" 2>&1
    $proc = & $Adb shell "pidof com.bxkj.student" 2>&1
    & $Adb shell "screencap -p /sdcard/mokr_s$i.png" 2>&1 | Out-Null
    & $Adb pull "/sdcard/mokr_s$i.png" "$Dir\shots\s$i.png" 2>&1 | Out-Null
    & $Adb shell "rm -f /sdcard/mokr_s$i.png" 2>&1 | Out-Null
    Add-Content -Path $samples -Encoding UTF8 -Value ("[{0}] sample#{1} target_pid={2}" -f $ts, $i, ($proc -join ",").Trim())
    if ($tick) { Add-Content -Path $samples -Encoding UTF8 -Value (($tick | Out-String).TrimEnd()) }
    else { Add-Content -Path $samples -Encoding UTF8 -Value "(no module log lines in this sample)" }
    Add-Content -Path $samples -Encoding UTF8 -Value "---"
    Start-Sleep -Seconds $IntervalSec
}
Add-Content -Path $samples -Encoding UTF8 -Value ("[{0}] sampling finished" -f (Get-Date -Format "HH:mm:ss"))