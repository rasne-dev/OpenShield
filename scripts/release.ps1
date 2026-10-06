param(
    [switch]$NoBump = $false,
    [switch]$NoBuild = $false
)

$ErrorActionPreference = "Stop"

$gradleFile = Join-Path $PSScriptRoot "..\app\build.gradle.kts"
if (-not (Test-Path $gradleFile)) {
    Write-Error "app/build.gradle.kts bulunamadi: $gradleFile"
    exit 1
}

$content = [System.IO.File]::ReadAllText($gradleFile, [System.Text.Encoding]::UTF8)

# Mevcut surum ve kod
if ($content -match 'versionCode\s*=\s*(\d+)') {
    $code = [int]$matches[1]
} else {
    Write-Error "versionCode eslesmedi."
    exit 1
}

if ($content -match 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"') {
    $major = [int]$matches[1]
    $minor = [int]$matches[2]
    $patch = [int]$matches[3]
    $currentName = "$major.$minor.$patch"
} else {
    Write-Error "versionName 3 basamakli formatta bulunamadi."
    exit 1
}

if (-not $NoBump) {
    $code = $code + 1
    $patch = $patch + 1
    if ($patch -gt 9) {
        $patch = 0
        $minor = $minor + 1
    }
    if ($minor -gt 9) {
        $minor = 0
        $major = $major + 1
    }
    $newName = "$major.$minor.$patch"

    $content = [regex]::Replace($content, 'versionCode\s*=\s*\d+', "versionCode = $code")
    $content = [regex]::Replace($content, 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`"")
    [System.IO.File]::WriteAllText($gradleFile, $content, [System.Text.Encoding]::UTF8)
    Write-Host "[SURUM] Guncellendi -> Versiyon: $newName (Kod: $code)"
} else {
    $newName = $currentName
    Write-Host "[SURUM] Mevcut Versiyon: $newName (Kod: $code)"
}

if ($NoBuild) {
    Write-Host "[BILGI] Derleme adimi atlandi (--NoBuild)."
    exit 0
}

Write-Host "[DERLEME] Sessizce Release APK derleniyor..."
$gradlew = (Join-Path $PSScriptRoot "..\gradlew.bat")

& cmd.exe /c "`"$gradlew`" assembleRelease --quiet"
if ($LASTEXITCODE -ne 0) {
    Write-Error "[HATA] Gradle derlemesi basarisiz oldu (Kod: $LASTEXITCODE)."
    exit $LASTEXITCODE
}

$apkSource = Join-Path $PSScriptRoot "..\app\build\outputs\apk\release\app-release.apk"
$apkTarget = Join-Path $PSScriptRoot "..\OpenShield-v$newName.apk"

if (Test-Path $apkSource) {
    Copy-Item $apkSource -Destination $apkTarget -Force
    Write-Host "[BASARILI] Cikti olusturuldu: $apkTarget"
} else {
    Write-Error "[HATA] Derlenmis APK dosyasi bulunamadi: $apkSource"
    exit 1
}
