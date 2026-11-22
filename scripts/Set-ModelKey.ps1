$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Local .env is missing. Initialize the project first.' }
$secret = Read-Host 'Paste the FULL DashScope API Key, then Enter (hidden; never send it in chat)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer).Trim()
    if ($plainKey.Length -ge 2) {
        $first = $plainKey[0]; $last = $plainKey[$plainKey.Length - 1]
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $plainKey = $plainKey.Substring(1, $plainKey.Length - 2).Trim()
        }
    }
    if ($plainKey.Contains('*')) { throw 'This looks like a masked key (asterisks). Copy the full original key, not the hidden value in the key list. No file changed.' }
    if ($plainKey -match '^(DASHSCOPE_API_KEY\s*=|Bearer\s+)') { throw 'Paste only the key itself, without DASHSCOPE_API_KEY= or Bearer. No file changed.' }
    if ($plainKey.Length -lt 16 -or $plainKey.Length -gt 8192) { throw 'Key is empty, incomplete or too long. Paste the full key only (16-8192 characters). No file changed.' }
    if ($plainKey -match '[^\x21-\x7E]') { throw 'Key contains internal whitespace or non-ASCII characters. Copy the full key again. No file changed.' }
    $lines = [IO.File]::ReadAllLines($envPath)
    $updated = New-Object 'System.Collections.Generic.List[string]'
    $found = $false
    foreach ($line in $lines) {
        if ($line -match '^DASHSCOPE_API_KEY=') {
            if (!$found) { $updated.Add('DASHSCOPE_API_KEY=' + $plainKey); $found = $true }
        } else { $updated.Add($line) }
    }
    if (!$found) { $updated.Add('DASHSCOPE_API_KEY=' + $plainKey) }
    [IO.File]::WriteAllLines($envPath, $updated.ToArray(), [Text.UTF8Encoding]::new($false))
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $plainKey = $null
    $secret.Dispose()
}
Write-Host 'Key saved locally. This confirms storage only; provider authentication still needs verification. Restart the backend to load it.'