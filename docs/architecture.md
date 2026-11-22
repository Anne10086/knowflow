# KnowFlow M1 architecture

Local, single-user Spring Boot REST backend. Only UTF-8 TXT is supported in this milestone.
The root Maven layout is intentional; no empty frontend or agent modules are included.

```text
HTTP /api → validated controller → IngestionService / RagService
                                    ├─ KnowledgeStore (JdbcClient) → PostgreSQL
                                    ├─ PrivateFileStore → data/uploads/<UUID>.txt
                                    ├─ TxtParser → overlapping, source-traceable chunks
                                    ├─ MilvusIndex → Spring AI MilvusVectorStore → Milvus
                                    └─ DashScopeAnswerGenerator → Spring AI Alibaba → DashScope
```

Upload validates filename, MIME, UTF-8 and size, writes the private original, and commits document/chunk UUIDs
before external indexing. The chunk UUID is also the Milvus primary/vector ID. A unique `(kb_id, sha256)`
constraint prevents duplicate content in one knowledge base. The same bytes can be uploaded to another base.
M1 uses synchronous upload (201 after READY); a concurrent status request can observe INDEXING.
An indexing failure returns 503; the original and journal remain available through the file list for explicit reindex.
The complete asynchronous ingestion-job queue and PDF/DOCX processing belong to M2.

Search includes a server-side `kbId == UUID` metadata filter, top K=5 and cosine threshold=0.65.
Results are independently rechecked against PostgreSQL for the selected knowledge base and READY status.
Only authoritative stored chunk content enters generation. No results means no generation call.
The model must return a structured answer, a sufficiency flag and literal quotations of retrieved chunks.
The backend validates IDs and exact quotations, rechecks document status and constructs filenames and offsets
from PostgreSQL. TXT has no reliable page number, so `pageNumber` is null. Offsets count Java UTF-16 code units
in normalized text (BOM removed, CR/CRLF converted to LF), not byte positions in the original file.

Deletion commits DELETING first, immediately excluding the document from answer sources.
Vector deletion calls the Milvus Java SDK directly by the journaled UUIDs and the Spring AI `doc_id` field.
It does not initialize the embedding model, provider API or Spring AI vector store. Missing model credentials
therefore do not prevent deletion; an unavailable Milvus still retains DELETING for retry. A missing collection
is an idempotent no-op, including before the first import creates its collection.
It then deletes saved vector IDs, private original and document/chunk rows. Failure retains the tombstone
and journal for retry. Partially indexed FAILED documents may retain vectors, but cannot enter generation;
reindex deletes the journaled IDs before inserting again. On startup INDEXING becomes FAILED/INTERRUPTED.
This recovery assumes one application instance; multiple active backend replicas are outside M1.

PostgreSQL, etcd, MinIO and Milvus use named Docker volumes. `compose down` preserves them;
do not use `down -v` unless deliberately discarding the knowledge base. Flyway manages SQL schema changes.
The embedding identity table prevents accidental reuse of a collection after changing the embedding model or dimension.
When changing embedding spaces, use a new collection and reindex every document before querying.

API keys are read from process environment. Optional PowerShell scripts import a local ignored `.env` without
overriding explicitly set process variables. No model endpoint is called when the key is blank.
The backend binds to loopback; this is a local prototype without user authentication, not a public hosting configuration.
Uploaded text goes to the configured model provider. Test fixtures are user-supplied fictional material.

The model has no tools, shell or network search. Document instructions remain untrusted content.
Citation checks constrain provenance but do not prove every generated sentence is semantically supported;
real-model adversarial and insufficient-evidence acceptance is required before calling M1 passed.
