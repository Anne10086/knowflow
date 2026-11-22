$ErrorActionPreference='Stop'
$stage=Join-Path ([IO.Path]::GetTempPath()) ('knowflow-key-input-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $stage 'scripts') -Force | Out-Null
$inputScript=Join-Path $stage 'scripts/Set-ModelKey.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Set-ModelKey.ps1') -Destination $inputScript
$envPath=Join-Path $stage '.env'
$old="POSTGRES_PASSWORD=test-placeholder-only`nDASHSCOPE_API_KEY=old-placeholder`n"
function Read-Host { param($Prompt,[switch]$AsSecureString) ConvertTo-SecureString $global:KnowFlowSyntheticInput -AsPlainText -Force }
$cases=@(
    @{Name='ordinary key';Input=('sk-'+('a'*40));Expected=('sk-'+('a'*40))},
    @{Name='long key and bearer punctuation';Input=('sk-'+('x'*600)+'.abc+/=$');Expected=('sk-'+('x'*600)+'.abc+/=$')},
    @{Name='outer whitespace and quotes';Input=('  "sk-'+('b'*40)+'"  ');Expected=('sk-'+('b'*40))},
    @{Name='masked value rejected';Input=('sk-'+('*'*40));Error='masked'},
    @{Name='embedded newline rejected';Input=('sk-'+('c'*20)+"`n"+('d'*20));Error='whitespace'},
    @{Name='variable assignment rejected';Input=('DASHSCOPE_API_KEY=sk-'+('e'*40));Error='key itself'},
    @{Name='short value rejected';Input='short';Error='incomplete'},
    @{Name='missing setting appended';Input=('sk-'+('f'*40));Expected=('sk-'+('f'*40));Seed="POSTGRES_PASSWORD=test-placeholder-only`n"},
    @{Name='duplicate setting consolidated';Input=('sk-'+('g'*40));Expected=('sk-'+('g'*40));Seed=($old+"DASHSCOPE_API_KEY=another-placeholder`n")}
)
$count=0
foreach($case in $cases) {
    $seed=$old; if($case.ContainsKey('Seed')){$seed=$case.Seed}
    [IO.File]::WriteAllText($envPath,$seed,[Text.UTF8Encoding]::new($false))
    $global:KnowFlowSyntheticInput=$case.Input; $failure=$null
    try { & $inputScript 6>$null } catch { $failure=$_.Exception.Message }
    if($case.ContainsKey('Error')) {
        if(!$failure -or !$failure.Contains($case.Error)){throw ('Unexpected failure type: '+$case.Name)}
        if([IO.File]::ReadAllText($envPath) -ne $seed){throw ('Rejected input modified file: '+$case.Name)}
    } else {
        if($failure){throw ('Valid synthetic input rejected: '+$case.Name+'; '+$failure)}
        $saved=@([IO.File]::ReadAllLines($envPath)|Where-Object {$_ -match '^DASHSCOPE_API_KEY='})
        if($saved.Count -ne 1 -or $saved[0] -ne ('DASHSCOPE_API_KEY='+$case.Expected)){throw ('Saved value mismatch: '+$case.Name)}
        if(!([IO.File]::ReadAllText($envPath)).Contains('POSTGRES_PASSWORD=test-placeholder-only')){throw 'Unrelated setting changed'}
    }
    $count++; Write-Output ('PASS: '+$case.Name)
}
Write-Output ('Tests passed: '+$count+'; PowerShell '+$PSVersionTable.PSVersion.ToString()+'. Only synthetic values in a temporary directory; real .env untouched.')