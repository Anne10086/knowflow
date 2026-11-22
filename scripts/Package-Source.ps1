param([string]$Destination)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if (!$Destination) { $Destination=Join-Path (Split-Path -Parent $projectRoot) 'KnowFlow-M1-source.zip' }
$stage=Join-Path $projectRoot ('target/package-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
# Deliberate allowlist: never copy .env, data/, target/, .git/ or arbitrary workspace files.
$items=@('pom.xml','.gitignore','.env.example','AGENTS.md','KnowFlow-V1-开发任务书.md','README.md','compose.yml','.mvn','infra','src','scripts','tests','docs')
foreach($item in $items) { Copy-Item -LiteralPath (Join-Path $projectRoot $item) -Destination $stage -Recurse }
$revision=& git -C $projectRoot rev-parse HEAD 2>$null
if ($LASTEXITCODE -eq 0) {
    [IO.File]::WriteAllText((Join-Path $stage 'SOURCE-REVISION.txt'), "$revision`n", [Text.UTF8Encoding]::new($false))
}
$secretFiles=Get-ChildItem -LiteralPath $stage -File -Recurse -Force | Where-Object {$_.Name -eq '.env' -or $_.Name -eq '.env.local'}
if ($secretFiles) { throw 'Unexpected credential file detected; refusing to package.' }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $Destination -Force
Write-Output ('Source archive: '+$Destination)
Get-FileHash -LiteralPath $Destination -Algorithm SHA256
