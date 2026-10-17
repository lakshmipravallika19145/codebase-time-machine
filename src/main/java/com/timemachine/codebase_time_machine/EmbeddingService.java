package com.timemachine.codebase_time_machine;

import java.util.List;
import java.util.Map;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    private static final int BATCH = 32;
    private static final int MAX_CHARS = 1500;

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;

    public EmbeddingService(EmbeddingModel embeddingModel, JdbcTemplate jdbc) {
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
    }

    // Embeds every chunk of this repo that doesn't have a vector yet.
    public int embedMissing(String repo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, file_path, kind, name, content FROM code_chunks "
                        + "WHERE repo = ? AND embedding IS NULL ORDER BY id", repo);
        int done = 0;
        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(i + BATCH, rows.size()));
            List<String> texts = batch.stream().map(this::textFor).toList();
            List<float[]> vectors = embeddingModel.embed(texts);
            for (int j = 0; j < batch.size(); j++) {
                jdbc.update("UPDATE code_chunks SET embedding = ?::vector WHERE id = ?",
                        toLiteral(vectors.get(j)), batch.get(j).get("id"));
                done++;
            }
        }
        return done;
    }

    public String embedQuery(String text) {
        return toLiteral(embeddingModel.embed(text));
    }

    // The text we embed: file name + kind + name + code, so searches can match file names too.
    private String textFor(Map<String, Object> r) {
        String content = (String) r.get("content");
        if (content.length() > MAX_CHARS) {
            content = content.substring(0, MAX_CHARS);
        }
        String name = r.get("name") == null ? "" : (String) r.get("name");
        return "File: " + r.get("file_path") + "\n" + r.get("kind") + " " + name + "\n" + content;
    }

    private static String toLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}