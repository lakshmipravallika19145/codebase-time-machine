package com.timemachine.codebase_time_machine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    // Embeds every code chunk of this repo that doesn't have a vector yet.
    public int embedMissing(String repo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, file_path, kind, name, content FROM code_chunks "
                        + "WHERE repo = ? AND embedding IS NULL ORDER BY id", repo);
        return embedRows(rows, this::textFor,
                "UPDATE code_chunks SET embedding = ?::vector WHERE id = ?");
    }

    // Embeds every commit of this repo that doesn't have a vector yet.
    public int embedCommitsMissing(String repo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, message, files FROM commit_chunks "
                        + "WHERE repo = ? AND embedding IS NULL ORDER BY id", repo);
        return embedRows(rows, this::commitTextFor,
                "UPDATE commit_chunks SET embedding = ?::vector WHERE id = ?");
    }

    public String embedQuery(String text) {
        return toLiteral(embeddingModel.embed(text));
    }

    private int embedRows(List<Map<String, Object>> rows,
                          Function<Map<String, Object>, String> textFor, String updateSql) {
        int done = 0;
        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(i + BATCH, rows.size()));
            List<String> texts = batch.stream().map(textFor).toList();
            List<float[]> vectors = embeddingModel.embed(texts);
            for (int j = 0; j < batch.size(); j++) {
                jdbc.update(updateSql, toLiteral(vectors.get(j)), batch.get(j).get("id"));
                done++;
            }
        }
        return done;
    }

    // Code text: file name + kind + name + code, so searches can match file names too.
    private String textFor(Map<String, Object> r) {
        String content = cap((String) r.get("content"), MAX_CHARS);
        String name = r.get("name") == null ? "" : (String) r.get("name");
        return "File: " + r.get("file_path") + "\n" + r.get("kind") + " " + name + "\n" + content;
    }

    // Commit text: the message plus the files it touched.
    private String commitTextFor(Map<String, Object> r) {
        return cap((String) r.get("message"), 1000) + "\nFiles changed:\n" + cap((String) r.get("files"), 500);
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
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