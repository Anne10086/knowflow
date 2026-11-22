CREATE TABLE knowledge_bases (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL REFERENCES knowledge_bases(id),
    filename VARCHAR(180) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    stored_filename VARCHAR(40) NOT NULL,
    bytes INTEGER NOT NULL CHECK(bytes > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADED','INDEXING','READY','FAILED','DELETING')),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, kb_id),
    UNIQUE (kb_id, sha256)
);
CREATE INDEX documents_kb_idx ON documents(kb_id);
CREATE TABLE chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    kb_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    content TEXT NOT NULL,
    start_char INTEGER NOT NULL CHECK (start_char >= 0),
    end_char INTEGER NOT NULL CHECK (end_char > start_char),
    FOREIGN KEY (document_id, kb_id) REFERENCES documents(id, kb_id) ON DELETE CASCADE,
    UNIQUE (document_id, ordinal)
);
CREATE INDEX chunks_kb_idx ON chunks(kb_id);
