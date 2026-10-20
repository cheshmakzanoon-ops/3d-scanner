# Explicit repair only. Normal builds use the verified wrapper committed to Git.
$ErrorActionPreference = 'Stop'
$rfRoot = Split-Path -Parent $PSScriptRoot
$rfFolder = Join-Path $rfRoot 'gradle\wrapper'
$rfTemporary = Join-Path ([IO.Path]::GetTempPath()) ('realityforge-' + [Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path $rfTemporary | Out-Null
try {
    $rfJar = Join-Path $rfTemporary 'wrapper.jar'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $rfJar
    $rfExpected = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
    $rfActual = (Get-FileHash -Algorithm SHA256 -Path $rfJar).Hash
    if ($rfActual -ine $rfExpected) { throw 'Gradle wrapper checksum mismatch' }
    Move-Item -Force $rfJar (Join-Path $rfFolder 'gradle-wrapper.jar')
    Write-Host 'Pinned Gradle 8.13 wrapper restored and verified. Distribution settings unchanged.'
} finally { Remove-Item -Recurse -Force $rfTemporary }
