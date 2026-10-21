CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS code_chunks (
    id         BIGSERIAL PRIMARY KEY,
    repo       VARCHAR(200) NOT NULL,
    file_path  TEXT NOT NULL,
    start_line INT NOT NULL,
    end_line   INT NOT NULL,
    kind       VARCHAR(20) NOT NULL,
    name       TEXT,
    content    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_code_chunks_repo ON code_chunks(repo);

ALTER TABLE code_chunks ADD COLUMN IF NOT EXISTS embedding vector(384);

ALTER TABLE code_chunks ADD COLUMN IF NOT EXISTS tsv tsvector GENERATED ALWAYS AS (
    to_tsvector('english',
        regexp_replace(
            regexp_replace(coalesce(file_path, '') || ' ' || coalesce(name, '') || ' ' || content,
                           '([a-z0-9])([A-Z])', '\1 \2', 'g'),
            '[^A-Za-z0-9]+', ' ', 'g'))
) STORED;

CREATE INDEX IF NOT EXISTS idx_code_chunks_tsv ON code_chunks USING gin(tsv);
CREATE TABLE IF NOT EXISTS commit_chunks (
    id           BIGSERIAL PRIMARY KEY,
    repo         VARCHAR(200) NOT NULL,
    hash         VARCHAR(40) NOT NULL,
    author       TEXT,
    committed_at TIMESTAMPTZ NOT NULL,
    message      TEXT NOT NULL,
    files        TEXT NOT NULL,
    additions    INT NOT NULL,
    deletions    INT NOT NULL,
    embedding    vector(384),
    tsv          tsvector GENERATED ALWAYS AS (
        to_tsvector('english',
            regexp_replace(
                regexp_replace(coalesce(message, '') || ' ' || coalesce(files, ''),
                               '([a-z0-9])([A-Z])', '\1 \2', 'g'),
                '[^A-Za-z0-9]+', ' ', 'g'))
    ) STORED,
    UNIQUE (repo, hash)
);
CREATE INDEX IF NOT EXISTS idx_commit_chunks_repo ON commit_chunks(repo);
CREATE INDEX IF NOT EXISTS idx_commit_chunks_tsv ON commit_chunks USING gin(tsv);
CREATE TABLE IF NOT EXISTS chunk_blame (
    id           BIGSERIAL PRIMARY KEY,
    chunk_id     BIGINT NOT NULL REFERENCES code_chunks(id) ON DELETE CASCADE,
    repo         VARCHAR(200) NOT NULL,
    hash         VARCHAR(40) NOT NULL,
    author       TEXT,
    committed_at TIMESTAMPTZ NOT NULL,
    message      TEXT NOT NULL,
    lines        INT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chunk_blame_chunk ON chunk_blame(chunk_id);
CREATE INDEX IF NOT EXISTS idx_chunk_blame_repo ON chunk_blame(repo);
CREATE TABLE IF NOT EXISTS eval_cases (
    id         BIGSERIAL PRIMARY KEY,
    repo       VARCHAR(200) NOT NULL,
    question   TEXT NOT NULL,
    file_path  TEXT NOT NULL,
    name       TEXT,
    start_line INT,
    end_line   INT,
    source     VARCHAR(20) NOT NULL DEFAULT 'manual'
);
CREATE INDEX IF NOT EXISTS idx_eval_cases_repo ON eval_cases(repo);