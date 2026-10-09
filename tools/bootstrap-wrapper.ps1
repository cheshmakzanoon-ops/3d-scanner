$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$folder = Join-Path $root 'gradle\wrapper'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('realityforge-' + [Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path $folder, $temp | Out-Null
try {
    $jar = Join-Path $temp 'wrapper.jar'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $jar
    $expected = (Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256').Content.Trim()
    $distribution = (Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').Content.Trim()
    if ($expected -notmatch '^[0-9a-fA-F]{64}$' -or $distribution -notmatch '^[0-9a-fA-F]{64}$') { throw 'Invalid checksum response' }
    $actual = (Get-FileHash -Algorithm SHA256 -Path $jar).Hash
    if ($actual -ine $expected) { throw 'Gradle wrapper checksum mismatch' }
    $properties = Join-Path $folder 'gradle-wrapper.properties'
    $text = ((Get-Content $properties | Where-Object { $_ -notmatch '^distributionSha256Sum=' }) -join "`n") + "`ndistributionSha256Sum=$distribution`n"
    [IO.File]::WriteAllText($properties, $text, (New-Object System.Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $folder 'gradle-wrapper.jar.sha256'), $expected)
    Move-Item -Force $jar (Join-Path $folder 'gradle-wrapper.jar')
    Write-Host 'Official Gradle wrapper downloaded and checksum verified.'
} finally { Remove-Item -Recurse -Force $temp }
