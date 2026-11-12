param(
    [string]$Version = "1.0.0"
)

# Builds the release APK and puts it into dist under its release name with a checksum.
# The version also stands in app/build.gradle.kts (versionName, versionCode): bump it there first.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "<JDK 17>"

# Gradle writes warnings to stderr; with Stop that would abort the script, so the build runs with Continue
$ErrorActionPreference = "Continue"
& "$root\gradlew.bat" -p $root assembleRelease
$code = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($code -ne 0) { throw "gradle failed: $code" }

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$from = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $from)) { throw "missing $from" }
$to = Join-Path $dist "LazyTV-$Version.apk"
Copy-Item -LiteralPath $from -Destination $to -Force
$sum = "{0}  {1}" -f (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash.ToLower(), (Split-Path -Leaf $to)
Set-Content -LiteralPath (Join-Path $dist "SHA256SUMS.txt") -Value $sum -Encoding ASCII

$size = [math]::Round((Get-Item -LiteralPath $to).Length / 1MB, 2)
$notes = Join-Path $dist "release-notes.md"
Write-Output "$to ($size MB)"
Write-Output $sum
Write-Output "next: gh release create v$Version `"$to`" --title `"LazyTV $Version`" --notes-file `"$notes`""
