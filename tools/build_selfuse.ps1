# Bypass Ads v1.0.0 self-use RELEASE build.
# Requires a third-party GKD subscription (JSON/JSON5) with splash rules.
# The complete build FAILS (never silently falls back to the small fixture)
# when the full rule stack cannot be produced.
#
# Signing:
#   - Reuses GKD_STORE_* if provided (gradle.properties or -PGKD_STORE_FILE=...).
#   - Otherwise auto-creates a STABLE long-term self-use key on first run at:
#       %USERPROFILE%\.bypass-ads\signing\bypass-selfuse.jks
#     (passwords live in signing.properties next to it, never in this repo,
#     never printed to the terminal; the key is reused on later builds).
#   - The release build NEVER uses the debug key. No formal key present ->
#     the script refuses to ship an APK.
#
# Output: dist\Bypass-Ads-v1.0.0-selfuse.apk  (signed, checked, verified)
#
# Usage:
#   .\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\gkd.json5"
#   .\tools\build_selfuse.ps1 -SubscriptionPaths "D:\rules\a.json5","D:\rules\b.json5"
param(
    [string]$SubscriptionPath,
    [string[]]$AdditionalSubscriptionPath = @(),
    [string[]]$SubscriptionPaths = @()
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$generator = Join-Path $PSScriptRoot "build_splash_bundle.py"
$localBundle = Join-Path $repoRoot "app\src\main\assets\bypass_splash_rules.local.json"
$VERSION_NAME = "1.0.0"
$distDir = Join-Path $repoRoot "dist"
$finalApk = Join-Path $distDir "Bypass-Ads-v$VERSION_NAME-selfuse.apk"
$signingDir = Join-Path $env:USERPROFILE ".bypass-ads\signing"
$signingProps = Join-Path $signingDir "signing.properties"
$keystore = Join-Path $signingDir "bypass-selfuse.jks"
$keyAlias = "bypass-selfuse"

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Bad($msg) { Write-Host "!! $msg" -ForegroundColor Red }

Write-Step "Bypass Ads $VERSION_NAME self-use RELEASE build"

# 1. validate inputs. The first source is primary; later sources add only
# non-conflicting coverage. The Python generator reports conflicts instead of
# concatenating incompatible same-name groups.
$ruleSources = @()
if ($SubscriptionPaths.Count -gt 0) {
    $ruleSources += $SubscriptionPaths
} elseif ($SubscriptionPath) {
    $ruleSources += $SubscriptionPath
    $ruleSources += $AdditionalSubscriptionPath
}
if ($ruleSources.Count -eq 0) {
    Write-Error "Provide -SubscriptionPath or -SubscriptionPaths."
    exit 1
}
foreach ($ruleSource in $ruleSources) {
    if (-not (Test-Path $ruleSource)) {
        Write-Error "Input subscription not found: $ruleSource"
        exit 1
    }
}
Write-Step "Primary subscription: $($ruleSources[0])"
if ($ruleSources.Count -gt 1) { Write-Step "Secondary subscriptions: $($ruleSources[1..($ruleSources.Count - 1)] -join ', ')" }

# 2. generate full bundle (filter + overrides + host rules + generic fallback + validate)
Write-Step "Generating full splash bundle (validator and coverage diff run inside generator)"
$generatorArgs = @($generator, $ruleSources[0])
if ($ruleSources.Count -gt 1) {
    foreach ($secondary in $ruleSources[1..($ruleSources.Count - 1)]) {
        $generatorArgs += "--additional"
        $generatorArgs += $secondary
    }
}
python @generatorArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Bundle generation FAILED - complete build aborted (no fixture fallback)."
    exit 1
}
if (-not (Test-Path $localBundle)) {
    Write-Error "Bundle output missing: $localBundle"
    exit 1
}
$bundleSize = (Get-Item $localBundle).Length
if ($bundleSize -lt 100000) {
    Write-Error "Bundle suspiciously small ($bundleSize bytes) - refusing to proceed (fixture fallback guard)."
    exit 1
}
$localBundleSha = ([System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.IO.File]::ReadAllBytes($localBundle))
)).Replace("-", "").ToLowerInvariant()
Write-Step "Bundle: $localBundle ($bundleSize bytes, SHA256=$localBundleSha)"

# 3. signing: reuse configured key, else create/load the stable self-use key.
$gradleSigningArgs = @()
$configured = [System.IO.File]::Exists((Join-Path $repoRoot "gradle.properties")) -and
    (Select-String -Path (Join-Path $repoRoot "gradle.properties") -Pattern "GKD_STORE_FILE" -Quiet)
if ($configured -or $env:GKD_STORE_FILE) {
    Write-Step "Using configured GKD_STORE_FILE signing key"
    if ($env:GKD_STORE_FILE) {
        $gradleSigningArgs += "-PGKD_STORE_FILE=$env:GKD_STORE_FILE"
        $gradleSigningArgs += "-PGKD_STORE_PASSWORD=$env:GKD_STORE_PASSWORD"
        $gradleSigningArgs += "-PGKD_KEY_ALIAS=$env:GKD_KEY_ALIAS"
        $gradleSigningArgs += "-PGKD_KEY_PASSWORD=$env:GKD_KEY_PASSWORD"
    }
    # gradle.properties -P properties are picked up by Gradle automatically.
} else {
    if (-not (Test-Path $signingDir)) { New-Item -ItemType Directory -Path $signingDir -Force | Out-Null }
    if (-not (Test-Path $keystore)) {
        Write-Step "No self-use key found - creating a stable one at $keystore"
        $storePass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
        $keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (-not (Test-Path $keytool)) { $keytool = "keytool" }
        # keytool logs its progress to stderr; PowerShell 5.1 treats stderr
        # lines as terminating errors under $ErrorActionPreference="Stop", so
        # run it through cmd and check the exit code explicitly.
        $keytoolCmd = "`"$keytool`" -genkeypair -keystore `"$keystore`" -storepass $storePass -alias $keyAlias -keyalg RSA -keysize 2048 -validity 36500 -dname `"CN=Bypass Ads Self-Use, OU=Self, O=Bypass Ads, L=Local, ST=Local, C=CN`" >nul 2>&1"
        cmd /c $keytoolCmd
        if ($LASTEXITCODE -ne 0) {
            Write-Error "keytool failed to create the self-use key."
            exit 1
        }
        # JDK9+ defaults to PKCS12, where storepass == keypass: only one
        # password is stored. Keep passwords ONLY in the user-home local file
        # (never the repo).
        @"
storeFile=$keystore
storePassword=$storePass
keyAlias=$keyAlias
keyPassword=$storePass
"@ | Set-Content -Path $signingProps -Encoding Ascii
        Write-Step "Self-use key created (passwords stored in $signingProps, never printed)"
    }
    if (-not (Test-Path $signingProps)) {
        Write-Error "signing.properties missing: $signingProps"
        exit 1
    }
    $props = @{}
    Get-Content $signingProps | ForEach-Object {
        if ($_ -match "^(storeFile|storePassword|keyAlias|keyPassword)=") {
            $kv = $_ -split "=", 2
            $props[$kv[0]] = $kv[1]
        }
    }
    $gradleSigningArgs += "-PGKD_STORE_FILE=$($props['storeFile'])"
    $gradleSigningArgs += "-PGKD_STORE_PASSWORD=$($props['storePassword'])"
    $gradleSigningArgs += "-PGKD_KEY_ALIAS=$($props['keyAlias'])"
    $gradleSigningArgs += "-PGKD_KEY_PASSWORD=$($props['keyPassword'])"
}

# 4. policy + JVM tests + release build
Push-Location $repoRoot
try {
    Write-Step "Running policy gates"
    python tools\test_splash_policy.py
    if ($LASTEXITCODE -ne 0) { Write-Error "test_splash_policy FAILED"; exit 1 }
    python tools\test_safety_exclusions.py
    if ($LASTEXITCODE -ne 0) { Write-Error "test_safety_exclusions FAILED"; exit 1 }
    python tools\test_multi_source.py
    if ($LASTEXITCODE -ne 0) { Write-Error "test_multi_source FAILED"; exit 1 }
    python tools\test_privacy_policy.py
    if ($LASTEXITCODE -ne 0) { Write-Error "test_privacy_policy FAILED"; exit 1 }

    Write-Step "JVM unit tests (testGkdDebugUnitTest)"
    $gradlew = Join-Path $repoRoot "gradlew.bat"
    & $gradlew --console=plain --offline :app:testGkdDebugUnitTest
    if ($LASTEXITCODE -ne 0) { Write-Error "JVM unit tests FAILED"; exit 1 }

    Write-Step "Building SIGNED release APK (assembleGkdRelease with GKD_STORE_*)"
    & $gradlew --console=plain --offline :app:assembleGkdRelease @gradleSigningArgs
    if ($LASTEXITCODE -ne 0) { Write-Error "Release build FAILED"; exit 1 }
} finally {
    Pop-Location
}

# 5. locate the signed APK and verify it is actually signed (release never
# falls back to the debug key: build.gradle.kts makes unsigned builds when no
# GKD_STORE_* is present, so a missing signature here is a hard failure).
$candidates = @(
    # Prefer the signed output. Gradle may leave an older unsigned sibling
    # beside it after an unsigned CI/R8 build; selecting that first would make
    # a valid self-use build fail its apksigner gate.
    (Join-Path $repoRoot "app\build\outputs\apk\gkd\release\app-gkd-release.apk"),
    (Join-Path $repoRoot "app\build\outputs\apk\gkd\release\app-gkd-release-unsigned.apk")
)
$apkPath = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apkPath) {
    Write-Error "Release APK not found (looked for $($candidates -join ', '))"
    exit 1
}
$apkSize = (Get-Item $apkPath).Length
Write-Step "Release APK: $apkPath ($apkSize bytes)"

# Locate the Android SDK: ANDROID_SDK_ROOT > ANDROID_HOME > local.properties sdk.dir
function Get-AndroidSdkPath {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    $localProps = Join-Path $repoRoot "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match "^sdk.dir=" } | Select-Object -First 1
        if ($line) {
            $sdkDir = ($line -replace "^sdk.dir=", "").Trim()
            $sdkDir = $sdkDir -replace "^\\\\", "" -replace "\\\\:", ":"
            if (Test-Path $sdkDir) { return $sdkDir }
        }
    }
    return $null
}
function Get-BuildToolsPath {
    param([string]$sdkPath, [string]$tool)
    $btRoot = Join-Path $sdkPath "build-tools"
    if (-not (Test-Path $btRoot)) { return $null }
    $versions = Get-ChildItem $btRoot -Directory | ForEach-Object { $_.Name } | Sort-Object { [version]$_ } -Descending
    foreach ($v in $versions) {
        # aapt/aapt2 are .exe; apksigner ships as apksigner.bat on Windows.
        $candidates = @(
            (Join-Path $btRoot "$v\$tool.exe"),
            (Join-Path $btRoot "$v\$tool.bat")
        )
        foreach ($candidate in $candidates) {
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}
$sdkPath = Get-AndroidSdkPath
if (-not $sdkPath) {
    Write-Error "Android SDK not found (set ANDROID_SDK_ROOT / ANDROID_HOME, or sdk.dir in local.properties)."
    exit 1
}
$aapt = Get-BuildToolsPath $sdkPath "aapt"
if (-not $aapt) {
    Write-Error "aapt not found under $sdkPath\build-tools"
    exit 1
}
$apksigner = Get-BuildToolsPath $sdkPath "apksigner"
if (-not $apksigner) {
    Write-Error "apksigner not found under $sdkPath\build-tools"
    exit 1
}

# 6. APK self-checks
$perms = & $aapt dump permissions $apkPath 2>&1
if (($perms | Select-String "android.permission.INTERNET") -ne $null) {
    Write-Error "APK contains INTERNET permission - refusing self-use build."
    exit 1
}
if (($perms | Select-String "android.permission.REQUEST_INSTALL_PACKAGES") -ne $null) {
    Write-Error "APK contains REQUEST_INSTALL_PACKAGES - refusing self-use build."
    exit 1
}
$badging = & $aapt dump badging $apkPath 2>&1
$verLine = $badging | Select-String "versionName='([^']+)'" | Select-Object -First 1
if ($verLine -and $verLine -notmatch "versionName='$VERSION_NAME'") {
    Write-Error "APK versionName is not $VERSION_NAME : $verLine"
    exit 1
}
$pkgLine = $badging | Select-String "package: name='([^']+)'" | Select-Object -First 1
if ($pkgLine -and $pkgLine -notmatch "name='app\.bypassads'") {
    Write-Error "APK package is not app.bypassads : $pkgLine"
    exit 1
}
$manifestXml = & $aapt dump xmltree $apkPath AndroidManifest.xml 2>&1
if ($manifestXml -match 'allowBackup="true"') {
    Write-Error "APK allowBackup=true - refusing self-use build."
    exit 1
}

# ML Kit discovers its registrars by reflection. Check the actual R8 mapping,
# not only debug tests: a missing constructor disables the local text model.
$mlkitManifest = Join-Path $repoRoot "app\build\intermediates\merged_manifests\gkdRelease\processGkdReleaseManifest\AndroidManifest.xml"
$releaseMapping = Join-Path $repoRoot "app\build\outputs\mapping\gkdRelease\mapping.txt"
python (Join-Path $repoRoot "tools\check_mlkit_release.py") --manifest $mlkitManifest --mapping $releaseMapping
if ($LASTEXITCODE -ne 0) {
    Write-Error "ML Kit release registration FAILED - refusing to ship the APK."
    exit 1
}

# 7. verify the APK actually packs the full local bundle (not the fixture)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
$entry = $zip.Entries | Where-Object { $_.FullName -eq "assets/bypass_splash_rules.local.json" }
if (-not $entry) {
    $zip.Dispose()
    Write-Error "APK does not contain bypass_splash_rules.local.json"
    exit 1
}
$ms = New-Object System.IO.MemoryStream
$es = $entry.Open()
$es.CopyTo($ms)
$es.Close()
$bytes = $ms.ToArray()
$ms.Close()
$zip.Dispose()
$apkBundleSha = ([System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
)).Replace("-", "").ToLowerInvariant()
if ($apkBundleSha -ne $localBundleSha) {
    Write-Error "APK bundle SHA256 mismatch: apk=$apkBundleSha local=$localBundleSha"
    exit 1
}

# 8. signature verification (must be a real signature, not debug)
$verifyOut = & $apksigner verify --verbose --print-certs $apkPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or ($verifyOut -match "DOES NOT VERIFY")) {
    Write-Error "apksigner verify FAILED - the release APK is not properly signed."
    exit 1
}
$certLine = $verifyOut | Select-String "Signer #1 certificate DN:.*CN=" | Select-Object -First 1
$certSha = $verifyOut | Select-String "certificate SHA-256 digest" | Select-Object -First 1
Write-Step "Signature: verified"
if ($certSha) { Write-Step "certificate SHA-256: $($certSha.ToString().Trim())" }

# 9. final copy to dist
if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir -Force | Out-Null }
Copy-Item -Force $apkPath $finalApk
$finalSize = (Get-Item $finalApk).Length
$finalSha = ([System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.IO.File]::ReadAllBytes($finalApk))
)).Replace("-", "").ToLowerInvariant()

# 10. report
Write-Host ""
Write-Host "==================================" -ForegroundColor Green
Write-Host " Bypass Ads $VERSION_NAME self-use RELEASE PASS" -ForegroundColor Green
Write-Host "==================================" -ForegroundColor Green
Write-Host "package       : app.bypassads"
Write-Host "version       : $VERSION_NAME"
Write-Host "APK path      : $finalApk"
Write-Host "APK size      : $finalSize bytes"
Write-Host "APK SHA256    : $finalSha"
Write-Host "bundle SHA256 : $localBundleSha"
Write-Host "INTERNET      : absent"
Write-Host "REQUEST_INSTALL_PACKAGES : absent"
Write-Host "allowBackup   : false"
Write-Host "signature     : verified"
Write-Host ""
Write-Host "LOCAL SELF-USE ONLY: this APK embeds third-party rule content and"
Write-Host "must NOT be publicly redistributed."
Write-Host ""
exit 0
