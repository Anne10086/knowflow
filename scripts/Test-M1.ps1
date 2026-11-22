param([switch]$Infrastructure, [switch]$RealModel, [string]$MavenCommand='mvn')
$ErrorActionPreference='Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
if ($Infrastructure) { $env:KNOWFLOW_INFRA_TESTS='true' } else { $env:KNOWFLOW_INFRA_TESTS='false' }
if ($RealModel) {
    if (!$env:DASHSCOPE_API_KEY) { throw 'Configure DASHSCOPE_API_KEY locally first. Real model tests are not passed or simulated.' }
    $env:KNOWFLOW_REAL_MODEL_TESTS='true'
} else { $env:KNOWFLOW_REAL_MODEL_TESTS='false' }
& $MavenCommand -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp verify
exit $LASTEXITCODE
