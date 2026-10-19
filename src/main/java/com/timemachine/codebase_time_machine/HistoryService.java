package com.timemachine.codebase_time_machine;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HistoryService {

    private final HistoryReader reader;
    private final EmbeddingService embeddings;
    private final JdbcTemplate jdbc;

    public HistoryService(HistoryReader reader, EmbeddingService embeddings, JdbcTemplate jdbc) {
        this.reader = reader;
        this.embeddings = embeddings;
        this.jdbc = jdbc;
    }

    public Map<String, Object> index(String repo, int maxCommits) throws Exception {
        List<HistoryReader.CommitInfo> commits = reader.read(Path.of("repos", repo), maxCommits);

        jdbc.update("DELETE FROM commit_chunks WHERE repo = ?", repo);
        jdbc.batchUpdate(
                "INSERT INTO commit_chunks (repo, hash, author, committed_at, message, files, additions, deletions) "
                        + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (repo, hash) DO NOTHING",
                commits, 200, (ps, c) -> {
                    ps.setString(1, repo);
                    ps.setString(2, c.hash());
                    ps.setString(3, c.author());
                    ps.setTimestamp(4, Timestamp.from(c.committedAt()));
                    ps.setString(5, c.message());
                    ps.setString(6, c.files());
                    ps.setInt(7, c.additions());
                    ps.setInt(8, c.deletions());
                });

        int embedded = embeddings.embedCommitsMissing(repo);
        return Map.of("repo", repo, "commits", commits.size(), "embedded", embedded);
    }
}