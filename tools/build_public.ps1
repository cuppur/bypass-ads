# Public, signed CN/EN APKs. No third-party local rule bundle is packaged.
param(
    [ValidateSet('zh', 'en')][string[]]$Languages = @('zh', 'en'),
    [switch]$Offline,
    [string]$GradleInitScript = '',
    [string]$SigningProperties = "$env:USERPROFILE\.bypass-ads\signing\signing.properties"
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = '1.0.1'
$dist = Join-Path $repoRoot 'dist\public'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
if (-not (Test-Path -LiteralPath $SigningProperties)) { throw 'Release signing properties are required outside the repository.' }
$signing = @{}
Get-Content -LiteralPath $SigningProperties | ForEach-Object {
    if ($_ -match '^(storeFile|storePassword|keyAlias|keyPassword)=') {
        $parts = $_ -split '=', 2
        $signing[$parts[0]] = $parts[1]
    }
}
foreach ($key in @('storeFile','storePassword','keyAlias','keyPassword')) {
    if (-not $signing[$key]) { throw "Missing signing property: $key" }
}
$sdkLine = Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
$sdk = ($sdkLine -split '=',2)[1].Replace('\\','\').Replace('\:',':')
$buildTools = Join-Path $sdk 'build-tools\37.0.0'
$signer = Join-Path $buildTools 'apksigner.bat'
$aapt = Join-Path $buildTools 'aapt.exe'
# Passwords are never command-line arguments, logs, source files or release assets.
$previousStorePassword = $env:BYPASS_PUBLIC_STORE_PASSWORD
$previousKeyPassword = $env:BYPASS_PUBLIC_KEY_PASSWORD
$env:BYPASS_PUBLIC_STORE_PASSWORD = $signing['storePassword']
$env:BYPASS_PUBLIC_KEY_PASSWORD = $signing['keyPassword']
$gradleNetworkArgs = @('-Dorg.gradle.internal.http.socketTimeout=30000','-Dorg.gradle.internal.http.connectionTimeout=15000')
if ($env:HTTPS_PROXY) {
    $proxy = [Uri]$env:HTTPS_PROXY
    $gradleNetworkArgs += @("-Dhttps.proxyHost=$($proxy.Host)","-Dhttps.proxyPort=$($proxy.Port)","-Dhttp.proxyHost=$($proxy.Host)","-Dhttp.proxyPort=$($proxy.Port)")
}
if ($Offline) { $gradleNetworkArgs += '--offline' }
if ($GradleInitScript) { $gradleNetworkArgs += @('--init-script', $GradleInitScript) }
Push-Location $repoRoot
try {
    foreach ($language in $Languages) {
        Write-Host "Building public $language Release $version"
        & .\gradlew.bat @gradleNetworkArgs :app:testGkdDebugUnitTest :app:assembleGkdRelease "-Pbypass.uiLanguage=$language" '-Pbypass.publicBuild=true' --console=plain --max-workers=4
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed for $language" }
        & python -X utf8 tools/check_mlkit_release.py --manifest app/build/intermediates/merged_manifests/gkdRelease/processGkdReleaseManifest/AndroidManifest.xml --mapping app/build/outputs/mapping/gkdRelease/mapping.txt
        if ($LASTEXITCODE -ne 0) { throw 'R8 registrar verification failed' }
        $source = Get-ChildItem -LiteralPath 'app\build\outputs\apk\gkd\release' -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $source) { throw 'Release APK not found' }
        $output = Join-Path $dist "Bypass-Ads-v$version-$language.apk"
        & $signer sign --ks $signing['storeFile'] --ks-key-alias $signing['keyAlias'] --ks-pass env:BYPASS_PUBLIC_STORE_PASSWORD --key-pass env:BYPASS_PUBLIC_KEY_PASSWORD --out $output $source.FullName
        if ($LASTEXITCODE -ne 0) { throw 'Release signing failed' }
        & $signer verify --verbose --print-certs $output
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
        & python -X utf8 tools/check_public_apk.py --apk $output --language $language --aapt $aapt
        if ($LASTEXITCODE -ne 0) { throw 'Public APK gate failed' }
        $symbols = Join-Path $dist "symbols-$language"
        New-Item -ItemType Directory -Force -Path $symbols | Out-Null
        Copy-Item -LiteralPath 'app\build\outputs\mapping\gkdRelease\mapping.txt' -Destination (Join-Path $symbols 'mapping.txt')
        $testResults = Join-Path $symbols 'unit-test-results'
        New-Item -ItemType Directory -Force -Path $testResults | Out-Null
        Get-ChildItem -LiteralPath 'app\build\test-results\testGkdDebugUnitTest' -Filter 'TEST-*.xml' | Copy-Item -Destination $testResults -Force
    }
    $lines = Get-ChildItem -LiteralPath $dist -Filter '*.apk' | Sort-Object Name | ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    }
    $lines | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ascii
} finally {
    $env:BYPASS_PUBLIC_STORE_PASSWORD = $previousStorePassword
    $env:BYPASS_PUBLIC_KEY_PASSWORD = $previousKeyPassword
    Pop-Location
}
