# 随心跑 (MokrRun) build script
# aapt2 -> javac -> d8 -> assemble -> zipalign -> apksigner
#
# 需要的外部环境（可用环境变量覆盖）：
#   MOKR_TOOLS   工具链目录，内含 jdk/、bt/android-14/、android.jar
#   MOKR_KS      签名 keystore 路径（默认 ./release.jks）
#   MOKR_KS_PASS keystore 密码（默认 changeme）
#
# 版本号由 version.txt 自增，构建时注入 manifest 与 BuildConfigTag
$ErrorActionPreference = "Continue"

$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$app    = Join-Path $root "app"
$out    = Join-Path $root "build"
$res    = Join-Path $app "src\main\res"
$javad  = Join-Path $app "src\main\java"
$assets = Join-Path $app "src\main\assets"
$manifest = Join-Path $app "AndroidManifest.xml"
$verFile  = Join-Path $root "version.txt"

$tools = if ($env:MOKR_TOOLS) { $env:MOKR_TOOLS } else { Join-Path (Split-Path -Parent $root) "build\tools" }

# ---- version ---------------------------------------------------------------
$prev = if (Test-Path $verFile) { [int]((Get-Content $verFile -Raw).Trim()) } else { 0 }
$verCode = $prev + 1
$verName = "1.0.$verCode"
$stamp   = (Get-Date).ToString("yyyyMMdd-HHmm")
Set-Content -Path $verFile -Value $verCode -Encoding ascii
Write-Host ("version: $verName (code=$verCode)") -ForegroundColor Yellow

$bctPath = Join-Path $javad "com\mokrun\app\BuildConfigTag.java"
$bct = "package com.mokrun.app;`r`n" +
       "public final class BuildConfigTag {`r`n" +
       "    public static final String VERSION = `"$verName`";`r`n" +
       "    public static final int CODE = $verCode;`r`n" +
       "    public static final String BUILD = `"$stamp`";`r`n" +
       "    private BuildConfigTag() {}`r`n" +
       "}`r`n"
[System.IO.File]::WriteAllText($bctPath, $bct, (New-Object System.Text.ASCIIEncoding))

# ---- toolchain -------------------------------------------------------------
$jdkBin  = (Get-ChildItem (Join-Path $tools "jdk") -Filter javac.exe -Recurse | Select-Object -First 1).DirectoryName
$javaExe = Join-Path $jdkBin "java.exe"
$javac   = Join-Path $jdkBin "javac.exe"
$bt      = Join-Path $tools "bt\android-14"
$aapt2   = Join-Path $bt "aapt2.exe"
$zipalign= Join-Path $bt "zipalign.exe"
$apksignerJar = Join-Path $bt "lib\apksigner.jar"
$androidJar = Join-Path $tools "android.jar"

$r8Local = Join-Path $tools "r8\r8.jar"
$d8jar = if (Test-Path $r8Local) { $r8Local } else { Join-Path $bt "lib\d8.jar" }

$keystore = if ($env:MOKR_KS) { $env:MOKR_KS } else { Join-Path $root "release.jks" }
$ksPass   = if ($env:MOKR_KS_PASS) { $env:MOKR_KS_PASS } else { "changeme" }

function Step($n, $msg) { Write-Host ("== [{0}/7] {1} ==" -f $n, $msg) -ForegroundColor Cyan }

Step 1 "clean"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $out "gen"), (Join-Path $out "classes"), (Join-Path $out "dex") | Out-Null

Step 2 "aapt2 compile"
& $aapt2 compile --dir $res -o (Join-Path $out "res.zip") 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Step 3 "aapt2 link"
$m = [System.IO.File]::ReadAllText($manifest)
$m = $m -replace 'android:versionCode="\d+"', ('android:versionCode="' + $verCode + '"')
$m = $m -replace 'android:versionName="[^"]*"', ('android:versionName="' + $verName + '"')
[System.IO.File]::WriteAllText($manifest, $m, (New-Object System.Text.UTF8Encoding($false)))

& $aapt2 link -o (Join-Path $out "base.apk") -I $androidJar --manifest $manifest --java (Join-Path $out "gen") --min-sdk-version 26 --target-sdk-version 36 --version-code $verCode --version-name $verName --auto-add-overlay (Join-Path $out "res.zip") 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Step 4 "javac"
$srcs = @()
$srcs += Get-ChildItem $javad -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem (Join-Path $out "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 8 -g -nowarn -classpath $androidJar -d (Join-Path $out "classes") $srcs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Step 5 "d8"
$classes = Get-ChildItem (Join-Path $out "classes\com") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $javaExe -cp $d8jar com.android.tools.r8.D8 --release --min-api 26 --lib $androidJar --output (Join-Path $out "dex") $classes 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Step 6 "assemble + align + sign"
$assemblePy = Join-Path $out "assemble.py"
$py = @"
import zipfile, os
base = r'$(Join-Path $out "base.apk")'
dex  = r'$(Join-Path $out "dex\classes.dex")'
assets = r'$assets'
outf = r'$(Join-Path $out "unsigned.apk")'
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(outf, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = it.compress_type
    zi.external_attr = it.external_attr
    zout.writestr(zi, zin.read(it.filename))
zi = zipfile.ZipInfo('classes.dex', date_time=(2024,1,1,0,0,0))
zi.compress_type = zipfile.ZIP_DEFLATED
zout.writestr(zi, open(dex,'rb').read())
for name in sorted(os.listdir(assets)):
    p = os.path.join(assets, name)
    if os.path.isfile(p):
        zi = zipfile.ZipInfo('assets/' + name, date_time=(2024,1,1,0,0,0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, open(p,'rb').read())
zout.close()
print('assembled', os.path.getsize(outf))
"@
$py | Out-File -FilePath $assemblePy -Encoding ascii
python $assemblePy 2>&1 | ForEach-Object { Write-Host $_ }

& $zipalign -p -f 4 (Join-Path $out "unsigned.apk") (Join-Path $out "aligned.apk") 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

# keystore 缺失时自动生成一个开发用密钥，保证 clone 后可直接构建
if (-not (Test-Path $keystore)) {
    Write-Host "keystore not found, generating a dev one: $keystore" -ForegroundColor Yellow
    $keytool = Join-Path $jdkBin "keytool.exe"
    & $keytool -genkeypair -keystore $keystore -alias app `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $ksPass -keypass $ksPass `
        -dname "CN=MokrRun Dev" -noprompt 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) {
        throw "failed to generate keystore"
    }
    Write-Host "dev keystore created (仅用于本地调试，不要用它发布)" -ForegroundColor Yellow
}

& $javaExe -jar $apksignerJar sign --ks $keystore --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" --out (Join-Path $out "MokrRun.apk") (Join-Path $out "aligned.apk") 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

& $javaExe -jar $apksignerJar verify (Join-Path $out "MokrRun.apk") 2>&1 | ForEach-Object { Write-Host $_ }

Step 7 "done"
Write-Host ("built " + (Join-Path $out "MokrRun.apk")) -ForegroundColor Green
Get-Item (Join-Path $out "MokrRun.apk") | Select-Object FullName, Length
