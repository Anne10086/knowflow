param([string]$MavenCommand='mvn', [string]$DockerCommand='docker')
$ErrorActionPreference='Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
# Only KnowFlow Compose dependencies are restarted. Named volumes are preserved.
$env:KNOWFLOW_RESTART_PHASE='seed'
& $MavenCommand -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp '-Dit.test=RestartPersistenceIT' verify
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $DockerCommand compose restart
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $DockerCommand compose up -d --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:KNOWFLOW_RESTART_PHASE='verify'
& $MavenCommand -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp '-Dit.test=RestartPersistenceIT' verify
exit $LASTEXITCODE
