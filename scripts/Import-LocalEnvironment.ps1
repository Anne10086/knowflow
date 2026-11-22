$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^(POSTGRES_PASSWORD|POSTGRES_USER|DATABASE_URL|DASHSCOPE_API_KEY|DASHSCOPE_BASE_URL|CHAT_MODEL|EMBEDDING_MODEL|MILVUS_HOST|MILVUS_PORT|MILVUS_COLLECTION|UPLOAD_DIR|SERVER_PORT)=(.*)$') {
            if (![Environment]::GetEnvironmentVariable($Matches[1], 'Process')) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            }
        }
    }
}
