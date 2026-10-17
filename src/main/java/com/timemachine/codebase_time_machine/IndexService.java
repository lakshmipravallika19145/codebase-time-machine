package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IndexService {

    private final RepoCloner cloner;
    private final CodeChunker chunker;
    private final JdbcTemplate jdbc;

    public IndexService(RepoCloner cloner, CodeChunker chunker, JdbcTemplate jdbc) {
        this.cloner = cloner;
        this.chunker = chunker;
        this.jdbc = jdbc;
    }

    public Map<String, Object> index(String url) throws Exception {
        Path dir = cloner.clone(url);
        String repo = cloner.repoName(url);
        List<Chunk> chunks = chunker.chunkRepo(dir);

        jdbc.update("DELETE FROM code_chunks WHERE repo = ?", repo);
        jdbc.batchUpdate(
                "INSERT INTO code_chunks (repo, file_path, start_line, end_line, kind, name, content) "
                        + "VALUES (?,?,?,?,?,?,?)",
                chunks, 500, (ps, c) -> {
                    ps.setString(1, repo);
                    ps.setString(2, c.filePath());
                    ps.setInt(3, c.startLine());
                    ps.setInt(4, c.endLine());
                    ps.setString(5, c.kind());
                    ps.setString(6, c.name());
                    ps.setString(7, c.content());
                });
        return Map.of("repo", repo, "chunks", chunks.size());
    }

    // Re-reads every saved line range from disk and checks the text really matches.
    public Map<String, Object> verify(String repo) throws IOException {
        Path root = Path.of("repos", repo);
        var rows = jdbc.queryForList(
                "SELECT file_path, start_line, end_line, content FROM code_chunks WHERE repo = ?", repo);
        int mismatches = 0;
        for (var r : rows) {
            List<String> lines = Files.readAllLines(root.resolve((String) r.get("file_path")));
            int s = (Integer) r.get("start_line");
            int e = (Integer) r.get("end_line");
            String actual = String.join("\n", lines.subList(s - 1, e));
            if (!actual.equals(r.get("content"))) {
                mismatches++;
            }
        }
        return Map.of("checked", rows.size(), "mismatches", mismatches);
    }
}