# 随心跑探针 (probe) build script
$ErrorActionPreference = "Continue"

$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools  = if ($env:MOKR_TOOLS) { $env:MOKR_TOOLS } else { Join-Path (Split-Path -Parent (Split-Path -Parent $root)) "build\tools" }
$out    = "$root\build"
$manifest = "$root\AndroidManifest.xml"
$javad  = "$root\src"
$res    = "$root\res"

$verCode = (Get-Date -Format "MMddHHmm") -as [int]
$verName = "1.0"

$jdkBin  = (Get-ChildItem "$tools\jdk" -Filter javac.exe -Recurse | Select-Object -First 1).DirectoryName
$javaExe = "$jdkBin\java.exe"
$javac   = "$jdkBin\javac.exe"
$bt      = "$tools\bt\android-14"
$aapt2   = "$bt\aapt2.exe"
$zipalign= "$bt\zipalign.exe"
$apksignerJar = "$bt\lib\apksigner.jar"
$r8Local = Join-Path $tools "r8\r8.jar"
$d8jar = if (Test-Path $r8Local) { $r8Local } else { Join-Path $tools "bt\android-14\lib\d8.jar" }
$androidJar = "$tools\android.jar"
$keystore = if ($env:MOKR_KS) { $env:MOKR_KS } else { Join-Path (Split-Path -Parent (Split-Path -Parent $root)) "release.jks" }
$ksPass = if ($env:MOKR_KS_PASS) { $env:MOKR_KS_PASS } else { "changeme" }

function Step($n, $msg) { Write-Host ("== [{0}/6] {1} ==" -f $n, $msg) -ForegroundColor Cyan }

Step 1 "clean"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$out\gen", "$out\classes", "$out\dex" | Out-Null

Step 2 "aapt2 compile"
& $aapt2 compile --dir $res -o "$out\res.zip" 2>&1 | ForEach-Object { Write-Host $_ }

Step 3 "aapt2 link"
& $aapt2 link -o "$out\base.apk" -I $androidJar --manifest $manifest --java "$out\gen" --min-sdk-version 26 --target-sdk-version 36 --version-code $verCode --version-name $verName --auto-add-overlay "$out\res.zip" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Step 4 "javac"
$srcs = @()
$srcs += Get-ChildItem $javad -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 8 -g -nowarn -classpath $androidJar -d "$out\classes" $srcs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Step 5 "d8"
$classes = Get-ChildItem "$out\classes\com" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $javaExe -cp $d8jar com.android.tools.r8.D8 --release --min-api 26 --lib $androidJar --output "$out\dex" $classes 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Step 6 "assemble + sign"
$assemble = @'
import zipfile, os
base = r'$base'
dex  = r'$dex'
outf = r'$outf'
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(outf, "w", zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = it.compress_type
    zi.external_attr = it.external_attr
    zout.writestr(zi, zin.read(it.filename))
zi = zipfile.ZipInfo("classes.dex", date_time=(2024,1,1,0,0,0))
zi.compress_type = zipfile.ZIP_DEFLATED
zout.writestr(zi, open(dex,"rb").read())
zout.close()
print("assembled", os.path.getsize(outf))
'@
$assemble | Out-File -FilePath "$out\assemble.py" -Encoding ascii
python "$out\assemble.py" 2>&1 | ForEach-Object { Write-Host $_ }

& $zipalign -p -f 4 "$out\unsigned.apk" "$out\aligned.apk" 2>&1 | ForEach-Object { Write-Host $_ }
if (-not (Test-Path $keystore)) {
    Write-Host "keystore not found, generating a dev one: $keystore" -ForegroundColor Yellow
    $keytool = Join-Path $jdkBin "keytool.exe"
    & $keytool -genkeypair -keystore $keystore -alias app `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $ksPass -keypass $ksPass `
        -dname "CN=MokrRun Dev" -noprompt 2>&1 | ForEach-Object { Write-Host $_ }
}

& $javaExe -jar $apksignerJar sign --ks $keystore --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" --out "$out\Probe.apk" "$out\aligned.apk" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "probe built" -ForegroundColor Green
Get-Item "$out\Probe.apk" | Select-Object FullName, Length
