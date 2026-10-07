# Windows equivalent of `./build.sh build run`: compile with javac, copy the web
# UI onto the classpath, and start the server. Requires JDK 21+ (javac on PATH
# or JAVA_HOME set). Usage:  powershell -ExecutionPolicy Bypass -File run.ps1 [-Test]
param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$bin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { $null }
$javac = if ($bin -and (Test-Path "$bin\javac.exe")) { "$bin\javac.exe" } else { 'javac' }
$java = if ($bin -and (Test-Path "$bin\java.exe")) { "$bin\java.exe" } else { 'java' }

function Compile([string]$target, [string[]]$dirs) {
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    New-Item $target -ItemType Directory | Out-Null
    $argfile = Join-Path $env:TEMP 'fraud-sources.txt'
    Get-ChildItem $dirs -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
        Set-Content $argfile -Encoding ascii
    & $javac -encoding UTF-8 -d $target "@$argfile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }
    Copy-Item 'src\main\resources\*' $target -Recurse -Force
}

if ($Test) {
    Compile 'out-test' @('src\main\java', 'src\test\java')
    $base = (Resolve-Path 'out-test').Path
    $failed = 0
    Get-ChildItem 'out-test' -Recurse -Filter *Test.class | Where-Object { $_.Name -notmatch '\$' } | ForEach-Object {
        $cn = $_.FullName.Substring($base.Length + 1) -replace '\\', '.' -replace '\.class$', ''
        & $java -cp 'out-test' $cn | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Host "PASS $cn" } else { Write-Host "FAIL $cn"; $failed++ }
    }
    if ($failed -gt 0) { throw "$failed test class(es) failed" }
    Write-Host 'All tests passed.'
    exit 0
}

Compile 'out' @('src\main\java')
& $java -cp 'out' com.frauddetector.App
