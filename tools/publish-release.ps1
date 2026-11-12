param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Repo = "ko22009/ltv"
)

# Publishes the release on GitHub without the gh CLI: tag v<Version> at the pushed HEAD, notes from
# dist/release-notes.md, the APK and checksums attached. Run tools/release.ps1 and push the commit first.
# The token comes from the git credential manager (the same one git push uses), it is never printed.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist"
$tag = "v$Version"
$apk = Join-Path $dist "LazyTV-$Version.apk"
$sums = Join-Path $dist "SHA256SUMS.txt"
$notesFile = Join-Path $dist "release-notes.md"
foreach ($p in @($apk, $sums, $notesFile)) { if (-not (Test-Path -LiteralPath $p)) { throw "missing $p" } }

# The checksum file must describe this very APK, otherwise a stale file from the previous version gets attached
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLower()
$sumsText = [IO.File]::ReadAllText($sums, [Text.Encoding]::ASCII)
if ($sumsText -notlike "$hash*LazyTV-$Version.apk*") { throw "SHA256SUMS.txt does not match $apk" }

# The tag is created by GitHub at this commit, so the commit has to be on the server already
$sha = (& git -C $root rev-parse HEAD).Trim()
$remote = (& git -C $root rev-parse origin/main).Trim()
if ($sha -ne $remote) { throw "HEAD $sha is not pushed (origin/main is $remote): git push first" }
if (& git -C $root status --porcelain) { throw "working tree has uncommitted changes" }

$cred = ("url=https://github.com`n`n" | git credential fill) | Out-String
$token = (($cred -split "`n") | Where-Object { $_ -like 'password=*' }) -replace 'password=', ''
$token = "$token".Trim()
if (-not $token) { throw "no github token in the credential manager" }

$headers = @{
    Authorization = "Bearer $token"
    "User-Agent" = "lazytv-release"
    Accept = "application/vnd.github+json"
}

$notes = [IO.File]::ReadAllText($notesFile, [Text.Encoding]::UTF8)
$payload = @{
    tag_name = $tag
    target_commitish = $sha
    name = "LazyTV $Version"
    body = $notes
    draft = $false
    prerelease = $false
}
# The body goes as UTF-8 bytes: PowerShell 5.1 would send a string in the system codepage and break Cyrillic
$bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 3))
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases" -Headers $headers `
    -Method Post -ContentType "application/json; charset=utf-8" -Body $bytes
Write-Output ("release: " + $release.html_url + " tag=" + $release.tag_name)

$files = @(
    @{ Path = $apk; Type = "application/vnd.android.package-archive" },
    @{ Path = $sums; Type = "text/plain" }
)
foreach ($f in $files) {
    $name = Split-Path -Leaf $f.Path
    $uri = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$name"
    $asset = Invoke-RestMethod -Uri $uri -Headers $headers -Method Post -ContentType $f.Type -InFile $f.Path
    Write-Output ("asset: " + $asset.name + " " + $asset.size + " bytes, state=" + $asset.state)
}

$latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers
Write-Output ("latest: " + $latest.tag_name + " " + $latest.html_url)
