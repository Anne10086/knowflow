$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) { Write-Host '.env already exists; preserved.'; exit 0 }
$random = [byte[]]::new(24)
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$password = [Convert]::ToHexString($random)
[IO.File]::WriteAllText($envPath, "POSTGRES_PASSWORD=$password`nDASHSCOPE_API_KEY=`n", [Text.UTF8Encoding]::new($false))
Write-Host 'Created ignored local .env with a random database password. Model key remains empty.'
