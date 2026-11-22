CREATE TABLE embedding_identity (
    collection_name VARCHAR(180) PRIMARY KEY,
    model_name VARCHAR(180) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
