param(
    [int]$Count = 10,
    [int]$IntervalSec = 15,
    [string]$Adb = "C:\Program Files\ASUS\GlideX\adb.exe",
    [string]$Dir = ""
)
$out = Join-Path $Dir "dense-3min.txt"
for ($i = 1; $i -le $Count; $i++) {
    $ts = Get-Date -Format "HH:mm:ss"
    $tick = & $Adb shell "logcat -d -v time | grep -E 'tick dist=' | tail -1" 2>&1
    $face = & $Adb shell "logcat -d -v time | grep -E 'face check' | tail -1" 2>&1
    & $Adb shell "screencap -p /sdcard/d$i.png" 2>&1 | Out-Null
    & $Adb pull "/sdcard/d$i.png" "$Dir\shots\dense$i.png" 2>&1 | Out-Null
    & $Adb shell "rm -f /sdcard/d$i.png" 2>&1 | Out-Null
    Add-Content -Path $out -Encoding UTF8 -Value ("[{0}] dense#{1} engine: {2}" -f $ts, $i, ($tick -join " ").Trim())
    if ($face) { Add-Content -Path $out -Encoding UTF8 -Value ("    face: " + ($face -join " ").Trim()) }
    Start-Sleep -Seconds $IntervalSec
}