param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference='Stop'
$text=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $Source).Path)
$projectRoot=Split-Path -Parent $PSScriptRoot
$userRoot=[Environment]::GetFolderPath('UserProfile')
foreach($mapping in @(@($projectRoot,'<PROJECT_DIR>'),@($userRoot,'<USER_HOME>'))) {
    if([string]::IsNullOrWhiteSpace($mapping[0])){continue}
    foreach($variant in @($mapping[0].Replace('\','\\'),$mapping[0],$mapping[0].Replace('\','/'))) {
        $text=$text.Replace($variant,$mapping[1])
    }
}
$text=$text.Replace(('started by '+[Environment]::UserName),'started by <LOCAL_USER>')
foreach($name in @('DASHSCOPE_API_KEY','DEEPSEEK_API_KEY','POSTGRES_PASSWORD')) {
    $secret=[Environment]::GetEnvironmentVariable($name)
    if($secret -and $secret.Length -ge 12){$text=$text.Replace($secret,'[REDACTED]')}
}
$text=[regex]::Replace($text,'(?:ghp_|github_pat_|sk-)[A-Za-z0-9_-]{20,}','[REDACTED]')
$text=(($text -split '\r?\n' | ForEach-Object {$_.TrimEnd()}) -join "`n")
[IO.File]::WriteAllText([IO.Path]::GetFullPath($Destination),$text,[Text.UTF8Encoding]::new($false))
Write-Output ('Exported sanitized evidence: '+[IO.Path]::GetFileName($Destination))
