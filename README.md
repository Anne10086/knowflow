# KnowFlow · M1 Java RAG backend

A local knowledge base with PostgreSQL metadata, private TXT originals, Milvus retrieval and DashScope generation.
**M1 passed independent static source review and evidence review on 2026-11-22.** The closeout regression passes 43 tests, including real model calls; see [actual test results](docs/test-report.md). M2 remains frozen.
No complete website is included: React, PDF/DOCX and mobile UI are M2 work.

## Requirements (Windows / WSL2)

1. Install a Java 21 JDK and Maven 3.9.x, set `JAVA_HOME` and add Java/Maven to PATH.
2. Install Docker Desktop with WSL2/Linux containers; enable virtualization and Docker's WSL integration.
   Run `wsl --status`, `java -version`, `mvn -version`, `docker version`, `docker compose version`.
   Docker **Server** must be reachable. Give Docker adequate free disk space and memory (8 GiB is a practical starting allocation
   for this local four-service stack and initial MinIO source build; actual need depends on corpus and workload).
3. Use PowerShell 7 for the supplied scripts (`pwsh`). Commands below run from this directory.
4. Obtain a DashScope key with access to `qwen-plus` and `text-embedding-v3` in the matching region.
   Document text is sent to this third-party provider. Use the supplied fictional fixtures for testing.

## Start

```powershell
pwsh -File scripts/Initialize-Local.ps1
# Creates an ignored .env with a random database password; preserves any existing .env.
pwsh -File scripts/Set-ModelKey.ps1
# Hidden input. Never paste the key into chat, source code, screenshots or terminal command arguments.
docker compose up -d --build --wait --wait-timeout 240
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp verify
pwsh -File scripts/Start-Backend.ps1
```

Initial MinIO source build can take several minutes and requires Internet access. Compose's wait timeout starts
for service startup, not total source compilation. Subsequent starts reuse the built image.
Backend: `http://127.0.0.1:18080`. PostgreSQL port 15432; Milvus 19530; Milvus health port 19091.
Only loopback database/API ports are published; MinIO and etcd stay on the private Compose network.
Do not run `docker compose config` in a screen capture: it expands local secrets.

The Java application reads environment variables, not `.env` automatically. `Start-Backend.ps1` imports the local file
using an allowlist, preserves explicitly set environment variables and starts the packaged jar.
For Maven development startup in one PowerShell session:

```powershell
. ./scripts/Import-LocalEnvironment.ps1
mvn -s .mvn/settings.xml -gs .mvn/settings.xml spring-boot:run
```

Without a key, metadata/empty-library operations work; uploads requiring embeddings return `MODEL_NOT_CONFIGURED`.
Health reports 503 with `model: NOT_CONFIGURED`. With a key, `CONFIGURED_NOT_PROBED` means configuration exists;
it does not certify credentials, quota, model access or a successful provider call.

In WSL2, install Linux Java/Maven and run Docker through Desktop integration. Copy `.env.example` to `.env`,
set a local database password and key, and export those variables in your shell before `mvn spring-boot:run`.
Do not put literal keys into shell history. PowerShell scripts are for Windows; keep `.env` private on either system.

## API smoke test (PowerShell 7)

```powershell
$base='http://127.0.0.1:18080/api'
$kb=(Invoke-RestMethod "$base/knowledge-bases" -Method Post -ContentType 'application/json' `
  -Body (@{name='蓝鲸资料库';description='人工测试资料'}|ConvertTo-Json -Compress)).knowledgeBase.id
$upload=Invoke-RestMethod "$base/knowledge-bases/$kb/documents" -Method Post `
  -Form @{file=Get-Item tests/fixtures/bluewhale.txt}
Invoke-RestMethod "$base/knowledge-bases/$kb/ask" -Method Post -ContentType 'application/json' `
  -Body (@{question='蓝鲸测试计划的版本代号是什么？'}|ConvertTo-Json -Compress)
Invoke-RestMethod "$base/documents/$($upload.document.id)/status"
Invoke-RestMethod "$base/documents/$($upload.document.id)" -Method Delete
```

Windows PowerShell 5 users should run these commands in `pwsh`, which supports multipart `-Form`.
Every API response has a request ID (also `X-Request-Id` header). Sources contain actual document/chunk IDs,
stored filenames, exact excerpts and normalized-text offsets. TXT page numbers are null.
Unknown information returns `grounded:false`, `insufficientEvidence:true`, no sources; provider failure is an HTTP error.

Implemented APIs:

| Method | Path |
|---|---|
| GET | `/api/health` |
| POST / GET | `/api/knowledge-bases` |
| POST / GET | `/api/knowledge-bases/{id}/documents` |
| GET | `/api/documents/{id}/status` |
| POST | `/api/documents/{id}/reindex` |
| DELETE | `/api/documents/{id}` |
| POST | `/api/knowledge-bases/{id}/ask` |

M1 accepts only valid UTF-8 TXT, max 2 MiB and 200,000 normalized characters.
Upload is synchronous: 201 after indexing; on failure, inspect GET documents and retry the saved document ID.
Duplicate bytes in one knowledge base return 409. MIME is restricted to text/plain or application/octet-stream.
The filename is metadata only; original files are stored under generated UUIDs outside any web directory.

## Tests and evidence

```powershell
pwsh -File scripts/Test-M1.ps1
# Unit + HTTP contract tests. Infrastructure and real model tests are explicitly skipped.
pwsh -File scripts/Test-M1.ps1 -Infrastructure
# Real PostgreSQL + Milvus; synthetic test-only embedding and mocked generation.
pwsh -File scripts/Test-M1.ps1 -Infrastructure -RealModel
# Adds genuine provider calls, may incur charges; throws if key is missing.
pwsh -File scripts/Test-Restart.ps1
# Two separate Java processes with real dependency restart; synthetic embedding, no model calls.
```

Use `-MavenCommand '<MAVEN_HOME>\bin\mvn.cmd'` if Maven is not on PATH, replacing the placeholder with your installation directory.
Test reports: `target/surefire-reports` and `target/failsafe-reports`.
`InfrastructureIT` never claims model correctness. `RealModelAcceptanceIT` tests actual TXT → DashScope embedding →
Milvus → generation → verified citations, unknown budget, cross-library refusal, injection text and deletion.
Do not call a skipped test a pass. See [test report](docs/test-report.md), [architecture](docs/architecture.md)
and [version decisions](docs/decisions.md). Supplied taskbook and fixtures were compared byte/text-wise with the user's attachments.

## Persistence and limits

Named volumes preserve PostgreSQL, etcd, MinIO and Milvus. Private files live in `data/uploads`.
Stop/restart with `docker compose stop` / `docker compose start`; **do not use `down -v`** to preserve data.
Failed imports cannot participate in answers; reindex uses saved vector IDs. Interrupted indexing becomes FAILED on restart.
Deletion failures remain DELETING and can be retried. Back up both named volumes and originals together.
Changing embedding model/dimension requires a new `MILVUS_COLLECTION` and reindexing; collection identity prevents silent reuse.
The current embedding dimension is fixed at 1024. Do not mix test-generated and real model vectors.

This M1 build assumes one local backend process, has no authentication or multi-user authorization, and is not ready for public hosting.
Citation verification proves quoted text exists in retrieved sources; it cannot mathematically prove answer semantics or eliminate hallucinations.
Real-model acceptance, durable asynchronous ingestion, broader failure/restart testing and user-facing UI remain separately tracked work.

旧控制台无法粘贴时，可在项目目录运行 `.\scripts\Set-ModelKey.ps1 -Window`，在本机遮挡窗口中点击粘贴和保存。窗口不预填现有密钥，保存成功仅代表配置写入；仍需真实模型验证。

Actual verified model setup: qwen-plus + text-embedding-v3 (1024 dimensions); DeepSeek has not been configured. Real model restart reproduction: `.\scripts\Test-RealRestart.ps1` (Maven and Docker on PATH). This restarts only the KnowFlow Compose services and preserves volumes.

Document deletion works after a cold start without a model key: it talks directly to Milvus without creating AI beans.
Unsupported outer request Content-Type returns HTTP 415 with a structured error and request ID.
Before publishing logs, run `./scripts/Export-PublicEvidence.ps1 -Source <local-log> -Destination <public-evidence>`.
It replaces workstation paths/usernames and masks known process credentials; review the output before publication.
Current evidence is sanitized. Earlier Git commits still retain original paths and author metadata; no history rewrite was performed.
