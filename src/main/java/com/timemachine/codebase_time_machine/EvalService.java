package com.timemachine.codebase_time_machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvalService {

    private static final int RESULT_K = 10;
    private static final long PAUSE_GENERATE_MS = 8000;
    private static final long PAUSE_ANSWER_MS = 20000;
    private static final int MAX_CASES_PER_FILE = 4;
    private static final Set<String> SKIP_NAMES = Set.of("toString", "equals", "hashCode", "main");

    private static final String GEN_SYSTEM = """
            You help evaluate a code search tool. You are given one method from a software project.
            Write ONE natural-language question that a developer might type when looking for this method, so that this method is the right answer.
            Rules:
            1. Describe what the code does or what it is used for, in plain words.
            2. Do not use the method name, the class name, or any other identifier from the code.
            3. Do not mention file names or line numbers.
            4. Keep it under 25 words.
            5. Output only the question, nothing else.
            """;

    public record Case(long id, String question, String filePath, String name, int startLine, int endLine) {}

    private final JdbcTemplate jdbc;
    private final SearchService search;
    private final AnswerService answers;
    private final ChatClient chatClient;
    private final Set<String> unusable = ConcurrentHashMap.newKeySet();

    public EvalService(JdbcTemplate jdbc, SearchService search, AnswerService answers,
                       ChatClient.Builder builder) {
        this.jdbc = jdbc;
        this.search = search;
        this.answers = answers;
        this.chatClient = builder.build();
    }

    // ---------- managing the test cases ----------

    public List<Map<String, Object>> list(String repo) {
        return jdbc.queryForList(
                "SELECT id, question, file_path, name, start_line, end_line, source "
                        + "FROM eval_cases WHERE repo = ? ORDER BY id", repo);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM eval_cases WHERE id = ?", id);
    }

    public Map<String, Object> addManual(String repo, String question, String file, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT start_line, end_line FROM code_chunks "
                        + "WHERE repo = ? AND file_path = ? AND name = ? ORDER BY start_line LIMIT 1",
                repo, file, name);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No indexed method with that name in that file path");
        }
        jdbc.update("INSERT INTO eval_cases (repo, question, file_path, name, start_line, end_line, source) "
                        + "VALUES (?,?,?,?,?,?, 'manual')",
                repo, question, file, name, rows.get(0).get("start_line"), rows.get(0).get("end_line"));
        return Map.of("added", true, "totalCases", load(repo).size());
    }

    private List<Case> load(String repo) {
        return jdbc.query(
                "SELECT id, question, file_path, name, start_line, end_line "
                        + "FROM eval_cases WHERE repo = ? ORDER BY id",
                (rs, i) -> new Case(rs.getLong("id"), rs.getString("question"), rs.getString("file_path"),
                        rs.getString("name"), rs.getInt("start_line"), rs.getInt("end_line")),
                repo);
    }
    private List<Case> load(String repo, String source) {
        if (source == null || source.isBlank()) {
            return load(repo);
        }
        return jdbc.query(
                "SELECT id, question, file_path, name, start_line, end_line "
                        + "FROM eval_cases WHERE repo = ? AND source = ? ORDER BY id",
                (rs, i) -> new Case(rs.getLong("id"), rs.getString("question"), rs.getString("file_path"),
                        rs.getString("name"), rs.getInt("start_line"), rs.getInt("end_line")),
                repo, source);
    }
    // ---------- drafting questions with the AI ----------

    public Map<String, Object> generate(String repo, int count) throws InterruptedException {
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT file_path, name, start_line, end_line, content FROM code_chunks "
                        + "WHERE repo = ? AND kind = 'method' AND file_path NOT LIKE '%src/test/%' "
                        + "AND end_line - start_line >= 4 AND length(content) BETWEEN 200 AND 1500 "
                        + "ORDER BY file_path, start_line", repo);
        Collections.shuffle(candidates, new Random(42)); // same order every time, so repeat calls continue

        Map<String, Integer> perFile = new HashMap<>();
        Set<String> existing = new HashSet<>();
        for (Case c : load(repo)) {
            perFile.merge(c.filePath(), 1, Integer::sum);
            existing.add(c.filePath() + "#" + c.startLine());
        }

        int added = 0;
        int leaky = 0;
        int failed = 0;
        for (Map<String, Object> c : candidates) {
            if (added >= count) {
                break;
            }
            String file = (String) c.get("file_path");
            String name = (String) c.get("name");
            int start = (Integer) c.get("start_line");
            int end = (Integer) c.get("end_line");
            String key = file + "#" + start;
            if (name == null || SKIP_NAMES.contains(name) || existing.contains(key)
                    || unusable.contains(key) || perFile.getOrDefault(file, 0) >= MAX_CASES_PER_FILE) {
                continue;
            }

            String question = writeQuestion((String) c.get("content"));
            if (question == null) {
                failed++;
                unusable.add(key);
                continue;
            }
            if (leaks(question, name, file)) {
                leaky++;
                unusable.add(key);
                continue;
            }
            jdbc.update("INSERT INTO eval_cases (repo, question, file_path, name, start_line, end_line, source) "
                    + "VALUES (?,?,?,?,?,?, 'generated')", repo, question, file, name, start, end);
            existing.add(key);
            perFile.merge(file, 1, Integer::sum);
            added++;
        }
        return Map.of("added", added, "rejectedForLeakingNames", leaky, "failed", failed,
                "totalCases", load(repo).size());
    }

    private String writeQuestion(String code) throws InterruptedException {
        String snippet = code.length() > 1200 ? code.substring(0, 1200) : code;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String raw = chatClient.prompt()
                        .system(GEN_SYSTEM)
                        .messages(new UserMessage("Method:\n" + snippet))
                        .call()
                        .content();
                Thread.sleep(PAUSE_GENERATE_MS); // stay under the free-tier token limit
                if (raw == null) {
                    return null;
                }
                String q = raw.strip().split("\\R", 2)[0].strip().replaceAll("^[\"'`]+|[\"'`]+$", "");
                return (q.length() >= 15 && q.length() <= 200) ? q : null;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("429") && attempt < 3) {
                    Thread.sleep(20000L * attempt);
                    continue;
                }
                return null;
            }
        }
        return null;
    }

    // A question that contains the method or class name would make keyword search win unfairly.
    private static boolean leaks(String question, String method, String file) {
        String q = question.toLowerCase();
        String cls = file.substring(file.lastIndexOf('/') + 1).replace(".java", "").toLowerCase();
        return q.contains(method.toLowerCase()) || q.contains(cls);
    }

    // ---------- the retrieval exam ----------

    public Map<String, Object> run(String repo, String source) {
        List<Case> cases = load(repo, source);
        if (cases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No eval cases yet. Generate or add some first.");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> misses = new ArrayList<>();

        for (String mode : List.of("vector", "keyword", "hybrid-baseline", "hybrid")) {
            int at1 = 0;
            int at3 = 0;
            int at5 = 0;
            double reciprocal = 0;
            long nanos = 0;
            for (Case c : cases) {
                long t0 = System.nanoTime();
                List<Hit> hits = searchFor(mode, repo, c.question());
                nanos += System.nanoTime() - t0;

                int rank = rankOf(c, hits);
                if (rank == 1) {
                    at1++;
                }
                if (rank >= 1 && rank <= 3) {
                    at3++;
                }
                if (rank >= 1 && rank <= 5) {
                    at5++;
                }
                if (rank > 0) {
                    reciprocal += 1.0 / rank;
                }
                if (mode.equals("hybrid") && (rank == 0 || rank > 5) && misses.size() < 15) {
                    Map<String, Object> miss = new LinkedHashMap<>();
                    miss.put("question", c.question());
                    miss.put("expected", c.filePath() + "  " + c.name());
                    miss.put("rank", rank == 0 ? "not in top " + RESULT_K : String.valueOf(rank));
                    miss.put("top3", hits.stream().limit(3)
                            .map(h -> h.filePath() + ":" + h.startLine() + "-" + h.endLine()
                                    + "  " + (h.name() == null ? "" : h.name()))
                            .toList());
                    misses.add(miss);
                }
            }
            int n = cases.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mode", mode);
            row.put("cases", n);
            row.put("recallAt1", round((double) at1 / n));
            row.put("recallAt3", round((double) at3 / n));
            row.put("recallAt5", round((double) at5 / n));
            row.put("mrr", round(reciprocal / n));
            row.put("avgMs", Math.round(nanos / 1_000_000.0 / n));
            results.add(row);
        }
        return Map.of("repo", repo, "results", results, "misses", misses);
    }

    private List<Hit> searchFor(String mode, String repo, String question) {
        boolean penalizeTests = !question.toLowerCase().contains("test");
        return switch (mode) {
            case "vector" -> search.vector(repo, question, RESULT_K);
            case "keyword" -> search.keyword(repo, question, RESULT_K);
            case "hybrid-baseline" -> search.hybridWith(repo, question, RESULT_K, penalizeTests, 1.0, 1.0);
            default -> search.hybrid(repo, question, RESULT_K);
        };
    }

    // 1-based rank of the first correct result, or 0 if it is not in the list
    private int rankOf(Case c, List<Hit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            if (matches(c, hits.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private boolean matches(Case c, Hit h) {
        if (!h.filePath().equals(c.filePath())) {
            return false;
        }
        if (c.name() != null && !c.name().isBlank()) {
            return c.name().equals(h.name());
        }
        return h.startLine() <= c.endLine() && h.endLine() >= c.startLine();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ---------- the end-to-end exam: full answers ----------

    @SuppressWarnings("unchecked")
    public Map<String, Object> runAnswers(String repo, int limit) throws InterruptedException {
        List<Case> all = load(repo);
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No eval cases yet.");
        }
        List<Case> chosen = all.subList(0, Math.min(Math.max(limit, 1), all.size()));
        int grounded = 0;
        int located = 0;
        int notFound = 0;
        int rejected = 0;
        int errors = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (Case c : chosen) {
            Map<String, Object> r = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    r = answers.answer(repo, c.question());
                } catch (Exception e) {
                    r = null;
                }
                boolean limited = r != null && String.valueOf(r.get("answer")).startsWith("The AI service is rate-limited");
                if (!limited) {
                    break;
                }
                r = null;
                Thread.sleep(40000);
            }
            if (r == null) {
                errors++;
                continue;
            }

            List<Map<String, Object>> cites = (List<Map<String, Object>>) r.get("citations");
            boolean isGrounded = !cites.isEmpty();
            boolean isLocated = cites.stream().anyMatch(m ->
                    c.filePath().equals(m.get("file"))
                            && (Integer) m.get("startLine") <= c.endLine()
                            && (Integer) m.get("endLine") >= c.startLine());
            if (isGrounded) {
                grounded++;
            }
            if (isLocated) {
                located++;
            }
            if ("Not found in the code.".equals(r.get("answer"))) {
                notFound++;
            }
            int rej = ((Number) r.get("rejectedCitations")).intValue();
            rejected += rej;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("question", c.question());
            d.put("expected", c.filePath() + "  " + c.name());
            d.put("grounded", isGrounded);
            d.put("citedTheRightCode", isLocated);
            d.put("rejectedCitations", rej);
            details.add(d);
            Thread.sleep(PAUSE_ANSWER_MS);
        }

        int done = chosen.size() - errors;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", repo);
        out.put("questionsAsked", chosen.size());
        out.put("answered", done);
        out.put("errors", errors);
        out.put("groundedRate", done == 0 ? 0 : round((double) grounded / done));
        out.put("citedTheRightCodeRate", done == 0 ? 0 : round((double) located / done));
        out.put("notFoundAnswers", notFound);
        out.put("totalRejectedCitations", rejected);
        out.put("details", details);
        return out;
    }
}