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