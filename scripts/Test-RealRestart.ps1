param([string]$MavenCommand='mvn',[string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
if(!$env:DASHSCOPE_API_KEY){throw 'A real model key is required; no simulated model is used.'}
$env:KNOWFLOW_INFRA_TESTS='false';$env:KNOWFLOW_REAL_MODEL_TESTS='false'
$env:KNOWFLOW_REAL_RESTART_PHASE='seed'
& $MavenCommand -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp '-Dit.test=RealRestartPersistenceIT' verify
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $DockerCommand compose restart
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $DockerCommand compose up -d --wait --wait-timeout 240
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$env:KNOWFLOW_REAL_RESTART_PHASE='verify'
& $MavenCommand -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp '-Dit.test=RealRestartPersistenceIT' verify
exit $LASTEXITCODE