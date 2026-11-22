# M1 technical decisions and version evidence

Verified on 2026-11-22. Versions are deliberately fixed to the requested compatible 3.5/1.1 family,
not presented as the newest releases. Actual resolution is recorded in `docs/evidence/dependency-tree.txt`.

| Component | Version / choice | Evidence |
|---|---|---|
| Java / Maven | 21.0.10 / 3.9.16 on this workstation | Executed version commands |
| Spring Boot | 3.5.16 | Maven parent resolved, compiled, test application started |
| Spring AI | 1.1.2 BOM | Resolved model and Milvus modules |
| Spring AI Alibaba | core BOM 1.1.2.0; extensions BOM and DashScope module 1.1.2.2 | Published module POM uses Spring AI 1.1.2 |
| Milvus Java SDK | 2.5.8 | Transitive pin from Spring AI Milvus 1.1.2 |
| Milvus server | 2.6.2 | Compose pin; runtime integration result in test report |
| PostgreSQL | 17.9-alpine | Compose pin |
| etcd | 3.5.18 | Upstream Milvus release Compose |
| MinIO | source RELEASE.2025-10-15T17-29-55Z | Local multistage build, official source via Go module checksum verification |
| Chat / embedding | qwen-plus / text-embedding-v3, 1024 dimensions | Configuration; real invocation requires local key |

Primary sources consulted:

- [Alibaba version documentation](https://www.java2ai.com/docs/versions/). This unversioned page has moved to newer families;
  the immutable release POM is more appropriate for this pinned implementation.
- [Alibaba v1.1.2.2 POM](https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.2/pom.xml): Spring AI 1.1.2 and Boot 3.5.8,
  supporting the selected compatible family. Boot 3.5.16 is validated by the local build/start tests, not claimed
  as the exact patch tested by upstream.
- [Spring AI Milvus documentation](https://docs.spring.io/spring-ai/reference/api/vectordbs/milvus.html), with the
  locally resolved 1.1.2 jar/POM used to verify actual APIs, SDK version and strong consistency.
- [Milvus v2.6.2 Compose source](https://github.com/milvus-io/milvus/blob/v2.6.2/deployments/docker/standalone/docker-compose.yml).
  Its server tag still says 2.6.1; this project's explicit 2.6.2 server is separately tested.
- [Upstream MinIO image failure report](https://github.com/milvus-io/milvus/issues/53430).
- [Official MinIO source release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z),
  [pinned go.mod](https://github.com/minio/minio/blob/RELEASE.2025-10-15T17-29-55Z/go.mod).
- [DashScope embedding API](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)
  documents text-embedding-v3 and the selected 1024-dimensional output;
  [generation API](https://www.alibabacloud.com/help/en/model-studio/qwen-api-via-dashscope) documents qwen-plus.
  These are provider model names, not immutable model-weight snapshots.

The old `minio/minio:RELEASE.2024-05-28T17-19-04Z` pull failed. Direct manifest checks also failed
for that version and RELEASE.2025-09-07T16-13-09Z on Quay. Rather than silently use a third-party image,
the Compose service builds official pinned MinIO source using Go 1.24.9 and Alpine 3.22.2.
The object store remains MinIO; neither PostgreSQL nor Milvus is substituted.
Initial build requires network access and is slower than pulling a ready-made image. Base image tags
and Alpine package repositories are versioned but not content-hash locked; offline, bit-identical rebuilds are not claimed.

Use `COSINE` + `FLAT` for the small local M1 corpus, favoring exact search over approximate-index tuning.
The threshold 0.65 is an initial setting, not calibrated semantic confidence. Real acceptance may require adjustment
based on observed positive and negative queries; do not tune to fixture codes or hard-code business answers.
Each vector stores kbId, documentId, chunkId, filename and chunkIndex. TXT has no page metadata.
Chunks are up to 800 UTF-16 code units with approximately 120 units of overlap, preserving paragraph breaks when possible.
Embedding batches are at most 10 texts, within provider batching limits. Chunk/vector IDs are persisted before any external write.

No front end, PDF/DOCX, knowledge-base rename/delete or multi-agent work is included in M1.
Document delete/reindex is included now because it makes failure recovery and provenance testable.
Synchronous import is an explicit M1 simplification; a durable asynchronous ingestion-job worker is deferred to M2.

Current user instructions explicitly request a local Git commit, superseding the older no-local-repository line in AGENTS.md.
No remote repository creation or publication is required to validate this milestone; a source ZIP can be supplied.
