$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
Set-Location (Split-Path -Parent $PSScriptRoot)
if (!$env:POSTGRES_PASSWORD) { throw 'POSTGRES_PASSWORD is missing. Run Initialize-Local.ps1.' }
if (!$env:DASHSCOPE_API_KEY) { Write-Warning 'Model key is missing: real embedding and generation cannot run. No fake results will be returned.' }
$javaCommand='java'
if ($env:JAVA_HOME) {
    $javaCommand=Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (!(Test-Path -LiteralPath $javaCommand)) { throw 'JAVA_HOME does not point to an installed Windows JDK.' }
}
& $javaCommand -jar target/knowflow-0.1.0-M1.jar
exit $LASTEXITCODE
