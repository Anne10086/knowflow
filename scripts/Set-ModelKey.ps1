$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Run Initialize-Local.ps1 with PowerShell 7 first.' }
$secret = Read-Host 'DashScope API Key (hidden; never send it in chat)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($plainKey -notmatch '^[A-Za-z0-9_-]{16,256}$') { throw 'Unexpected key format; no file changed.' }
    $content = [IO.File]::ReadAllText($envPath)
    $content = [regex]::Replace($content, '(?m)^DASHSCOPE_API_KEY=.*$', "DASHSCOPE_API_KEY=$plainKey")
    [IO.File]::WriteAllText($envPath, $content, [Text.UTF8Encoding]::new($false))
} finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $plainKey = $null; $secret.Dispose() }
Write-Host 'Key saved locally in ignored .env. Restart the backend to load it.'
