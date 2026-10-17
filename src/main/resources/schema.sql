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