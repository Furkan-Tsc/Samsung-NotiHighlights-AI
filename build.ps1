$ErrorActionPreference = 'Stop'
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'

$Root = $PSScriptRoot
$JdkBin = 'C:\Users\furki\.jdks\ms-17.0.20\bin'
$Javac = Join-Path $JdkBin 'javac.exe'
$Jar = Join-Path $JdkBin 'jar.exe'
$Keytool = Join-Path $JdkBin 'keytool.exe'

$AndroidJar = 'C:\Users\furki\AppData\Local\Android\Sdk\platforms\android-36\android.jar'
$BuildTools = 'C:\Users\furki\AppData\Local\Android\Sdk\build-tools\36.0.0'
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'

$BuildDir = Join-Path $Root 'build'
$StubsOut = Join-Path $BuildDir 'stubs'
$GenOut = Join-Path $BuildDir 'gen'
$ClassesOut = Join-Path $BuildDir 'classes'
$DexOut = Join-Path $BuildDir 'dex'
$Keystore = Join-Path $Root 'notihighlights_tr.jks'

if (Test-Path $BuildDir) {
    Remove-Item -Recurse -Force $BuildDir
}
New-Item -ItemType Directory -Force -Path $StubsOut, $GenOut, $ClassesOut, $DexOut | Out-Null

if (-not (Test-Path $Keystore)) {
    Write-Host 'Generating keystore...'
    $p = Start-Process -FilePath $Keytool -ArgumentList @(
        '-genkeypair', '-v',
        '-keystore', $Keystore,
        '-storepass', 'notihighlights',
        '-alias', 'notihighlights',
        '-keypass', 'notihighlights',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=NotiHighlightsTR,O=LocalMod,C=TR'
    ) -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "keytool failed with exit code $($p.ExitCode)" }
}

Write-Host '1. Compiling Xposed stubs...'
$StubFiles = Get-ChildItem -Recurse (Join-Path $Root 'xposed-stubs') -Filter '*.java' | Select-Object -ExpandProperty FullName
& $Javac -source 17 -target 17 -encoding UTF-8 -classpath $AndroidJar -d $StubsOut $StubFiles
if ($LASTEXITCODE -ne 0) { throw 'javac stubs failed' }
$StubsJar = Join-Path $BuildDir 'xposed_stubs.jar'
& $Jar cf $StubsJar -C $StubsOut .
if ($LASTEXITCODE -ne 0) { throw 'jar stubs failed' }

Write-Host '2. Compiling resources...'
$ResZip = Join-Path $BuildDir 'res.zip'
& $Aapt2 compile --dir (Join-Path $Root 'res') -o $ResZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '3. Linking base APK...'
$UnsignedBase = Join-Path $BuildDir 'unsigned_base.apk'
& $Aapt2 link -I $AndroidJar `
    --manifest (Join-Path $Root 'AndroidManifest.xml') `
    -A (Join-Path $Root 'assets') `
    --java $GenOut `
    -o $UnsignedBase `
    $ResZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '4. Compiling app Java sources...'
$SrcFiles = @()
$SrcFiles += Get-ChildItem -Recurse (Join-Path $Root 'src') -Filter '*.java' | Select-Object -ExpandProperty FullName
$SrcFiles += Get-ChildItem -Recurse $GenOut -Filter '*.java' | Select-Object -ExpandProperty FullName
& $Javac -source 17 -target 17 -encoding UTF-8 -classpath "$AndroidJar;$StubsJar" -d $ClassesOut $SrcFiles
if ($LASTEXITCODE -ne 0) { throw 'javac app failed' }

Write-Host '5. Running d8...'
$ClassFiles = Get-ChildItem -Recurse $ClassesOut -Filter '*.class' | Select-Object -ExpandProperty FullName
& $D8 --min-api 33 --lib $AndroidJar --output $DexOut $ClassFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '6. Packaging classes.dex into APK...'
$UnsignedApk = Join-Path $BuildDir 'unsigned.apk'
Copy-Item -Force $UnsignedBase $UnsignedApk
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip,
        (Join-Path $DexOut 'classes.dex'),
        'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

Write-Host '7. Aligning APK...'
$AlignedApk = Join-Path $BuildDir 'aligned.apk'
& $ZipAlign -f -p 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '8. Signing APK...'
$OutApk = Join-Path $BuildDir 'NotiHighlightsAI.apk'
& $ApkSigner sign `
    --ks $Keystore `
    --ks-pass pass:notihighlights `
    --key-pass pass:notihighlights `
    --out $OutApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

& $ApkSigner verify --print-certs $OutApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

Write-Host "SUCCESS: Built $OutApk"
