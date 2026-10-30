package com.timemachine.codebase_time_machine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private static final int POOL = 20;   // how many results each librarian brings
    private static final int RRF_K = 60; // standard constant for rank fusion
    private static final double NON_CODE_FACTOR = 0.5; // SQL, HTML, docs and config chunks count half

    private static final RowMapper<Hit> HIT_MAPPER = (rs, i) -> new Hit(
            rs.getLong("id"), rs.getString("file_path"),
            rs.getInt("start_line"), rs.getInt("end_line"),
            rs.getString("kind"), rs.getString("name"),
            rs.getString("content"), rs.getDouble("score"));

    private final EmbeddingService embeddings;
    private final JdbcTemplate jdbc;

    public SearchService(EmbeddingService embeddings, JdbcTemplate jdbc) {
        this.embeddings = embeddings;
        this.jdbc = jdbc;
    }

    // Librarian 1: search by meaning
    public List<Hit> vector(String repo, String question, int k) {
        String q = embeddings.embedQuery(question);
        return jdbc.query(
                "SELECT id, file_path, start_line, end_line, kind, name, content, "
                        + "1 - (embedding <=> ?::vector) AS score "
                        + "FROM code_chunks WHERE repo = ? AND embedding IS NOT NULL "
                        + "ORDER BY embedding <=> ?::vector LIMIT ?",
                HIT_MAPPER, q, repo, q, k);
    }

    // Librarian 2: search by exact words
    public List<Hit> keyword(String repo, String question, int k) {
        String tsQuery = toTsQuery(question);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT c.id, c.file_path, c.start_line, c.end_line, c.kind, c.name, c.content, "
                        + "ts_rank_cd(c.tsv, q) AS score "
                        + "FROM code_chunks c, to_tsquery('english', ?) q "
                        + "WHERE c.repo = ? AND c.tsv @@ q "
                        + "ORDER BY score DESC LIMIT ?",
                HIT_MAPPER, tsQuery, repo, k);
    }

    // Best chunks (by meaning) inside a given set of files.
    public List<Hit> inFiles(String repo, String question, List<String> files, int k) {
        if (files.isEmpty()) {
            return List.of();
        }
        String q = embeddings.embedQuery(question);
        String marks = String.join(",", Collections.nCopies(files.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(q);
        args.add(repo);
        args.addAll(files);
        args.add(q);
        args.add(k);
        return jdbc.query(
                "SELECT id, file_path, start_line, end_line, kind, name, content, "
                        + "1 - (embedding <=> ?::vector) AS score "
                        + "FROM code_chunks WHERE repo = ? AND embedding IS NOT NULL AND file_path IN (" + marks + ") "
                        + "ORDER BY embedding <=> ?::vector LIMIT ?",
                HIT_MAPPER, args.toArray());
    }
    // The best chunks (by meaning) of EACH given file, best-ranked first across files.
    public List<Hit> perFile(String repo, String question, List<String> files, int perFile) {
        if (files.isEmpty()) {
            return List.of();
        }
        String q = embeddings.embedQuery(question);
        String marks = String.join(",", Collections.nCopies(files.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(q);
        args.add(q);
        args.add(repo);
        args.addAll(files);
        args.add(perFile);
        return jdbc.query(
                "SELECT id, file_path, start_line, end_line, kind, name, content, score FROM ("
                        + "SELECT id, file_path, start_line, end_line, kind, name, content, "
                        + "1 - (embedding <=> ?::vector) AS score, "
                        + "ROW_NUMBER() OVER (PARTITION BY file_path ORDER BY embedding <=> ?::vector) AS rn "
                        + "FROM code_chunks WHERE repo = ? AND embedding IS NOT NULL AND file_path IN (" + marks + ")"
                        + ") t WHERE rn <= ? ORDER BY rn, score DESC",
                HIT_MAPPER, args.toArray());
    }
    // Both librarians, merged. Test files are down-weighted unless the question mentions tests.
    public List<Hit> hybrid(String repo, String question, int k) {
        boolean wantsTests = question.toLowerCase().contains("test");
        return hybridWith(repo, question, k, !wantsTests, 1.0, NON_CODE_FACTOR);
    }

    public List<Hit> hybridWith(String repo, String question, int k, boolean penalizeTests) {
        return hybridWith(repo, question, k, penalizeTests, 1.0, 1.0);
    }

    public List<Hit> hybridWith(String repo, String question, int k,
                                boolean penalizeTests, double keywordWeight) {
        return hybridWith(repo, question, k, penalizeTests, keywordWeight, 1.0);
    }

    // keywordWeight: smaller = the keyword list counts for less.
    // nonCodeFactor: smaller = SQL, HTML, docs and config chunks count for less,
    // unless the question itself mentions such files.
    public List<Hit> hybridWith(String repo, String question, int k, boolean penalizeTests,
                                double keywordWeight, double nonCodeFactor) {
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, Hit> byId = new HashMap<>();
        addRanks(vector(repo, question, POOL), fused, byId, 1.0);
        addRanks(keyword(repo, question, POOL), fused, byId, keywordWeight);

        if (penalizeTests) {
            fused.replaceAll((id, score) ->
                    byId.get(id).filePath().contains("src/test/") ? score * 0.5 : score);
        }
        if (nonCodeFactor < 1.0 && !mentionsNonCode(question)) {
            fused.replaceAll((id, score) ->
                    isNonCode(byId.get(id).filePath()) ? score * nonCodeFactor : score);
        }

        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(k)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }

    private static boolean isNonCode(String path) {
        return !path.toLowerCase().matches(".*\\.(java|py|js|jsx|ts|tsx)$");
    }

    private static boolean mentionsNonCode(String question) {
        String q = question.toLowerCase();
        for (String w : List.of("sql", "schema", "template", "html", "css", "config", "properties",
                "yaml", "yml", "xml", "readme", "docs", "script", "pom", "gradle")) {
            if (q.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private void addRanks(List<Hit> hits, Map<Long, Double> fused, Map<Long, Hit> byId, double weight) {
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            fused.merge(h.id(), weight / (RRF_K + i + 1), Double::sum);
            byId.putIfAbsent(h.id(), h);
        }
    }

    // "how is processCreationForm used?" -> "how | is | process | Creation | Form | used"
    static String toTsQuery(String question) {
        String spaced = question
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim();
        if (spaced.isEmpty()) {
            return "";
        }
        return Arrays.stream(spaced.split(" "))
                .filter(t -> t.length() > 1)
                .distinct()
                .collect(Collectors.joining(" | "));
    }
}