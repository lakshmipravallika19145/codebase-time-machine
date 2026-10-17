package com.timemachine.codebase_time_machine;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final EmbeddingService embeddings;
    private final JdbcTemplate jdbc;

    public SearchService(EmbeddingService embeddings, JdbcTemplate jdbc) {
        this.embeddings = embeddings;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String repo, String question, int k) {
        String q = embeddings.embedQuery(question);
        return jdbc.queryForList(
                "SELECT file_path, start_line, end_line, kind, name, "
                        + "1 - (embedding <=> ?::vector) AS score, left(content, 300) AS preview "
                        + "FROM code_chunks WHERE repo = ? AND embedding IS NOT NULL "
                        + "ORDER BY embedding <=> ?::vector LIMIT ?",
                q, repo, q, k);
    }
}