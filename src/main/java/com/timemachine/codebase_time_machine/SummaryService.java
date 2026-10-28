package com.timemachine.codebase_time_machine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {

    private static final int EXCERPT_CHARS = 2500;
    private static final long PAUSE_MS = 15_000;

    private static final String SYSTEM = """
            You describe one source file from a software project.
            Write exactly ONE sentence of at most 25 words saying what the file is for and what role it plays in the project, in plain words a beginner understands.
            Start with a verb such as Handles, Defines, Provides, Stores or Configures.
            Never write the file path or file name. Use only the code excerpt and names you are given. If the purpose is unclear, say what you can see, briefly.
            Output only the sentence.
            """;

    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final String model;
    private final Set<String> skipThisSession = ConcurrentHashMap.newKeySet();

    public SummaryService(JdbcTemplate jdbc, ChatClient.Builder builder,
                          @Value("${app.summary.model:openai/gpt-oss-20b}") String model) {
        this.jdbc = jdbc;
        this.chatClient = builder.build();
        this.model = model;
    }

    // Describes files that have no description yet: rules first (free), then up to `limit` files with AI.
    public Map<String, Object> generate(String repo, int limit) throws InterruptedException {
        List<String> todo = jdbc.queryForList(
                "SELECT DISTINCT c.file_path FROM code_chunks c WHERE c.repo = ? AND NOT EXISTS "
                        + "(SELECT 1 FROM file_summaries s WHERE s.repo = c.repo AND s.file_path = c.file_path) "
                        + "ORDER BY c.file_path", String.class, repo);

        int byRules = 0;
        List<String> needAi = new ArrayList<>();
        for (String path : todo) {
            String rule = FileRules.describe(path);
            if (rule != null) {
                save(repo, path, rule, "rule");
                byRules++;
            } else if (!skipThisSession.contains(path)) {
                needAi.add(path);
            }
        }

        int byAi = 0;
        int failed = 0;
        for (String path : needAi) {
            if (byAi + failed >= limit) {
                break;
            }
            String summary = summarize(repo, path);
            if (summary == null) {
                failed++;
                skipThisSession.add(path);
            } else {
                save(repo, path, summary, "ai");
                byAi++;
            }
            Thread.sleep(PAUSE_MS);
        }
        return Map.of("repo", repo, "describedByRules", byRules, "describedByAi", byAi,
                "failed", failed, "stillWaiting", needAi.size() - byAi - failed);
    }

    private String summarize(String repo, String path) throws InterruptedException {
        List<Map<String, Object>> chunks = jdbc.queryForList(
                "SELECT name, content FROM code_chunks WHERE repo = ? AND file_path = ? ORDER BY start_line",
                repo, path);
        StringBuilder code = new StringBuilder();
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> c : chunks) {
            String n = (String) c.get("name");
            if (n != null && !n.isBlank() && names.size() < 30) {
                names.add(n);
            }
            if (code.length() < EXCERPT_CHARS) {
                code.append(c.get("content")).append('\n');
            }
        }
        String excerpt = code.length() > EXCERPT_CHARS
                ? code.substring(0, EXCERPT_CHARS)
                : code.toString();
        String prompt = "File path: " + path + "\n"
                + (names.isEmpty() ? "" : "Methods and functions: " + String.join(", ", names) + "\n")
                + "\nCode excerpt:\n" + excerpt;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String raw = chatClient.prompt()
                        .system(SYSTEM)
                        .options(ChatOptions.builder().model(model))
                        .messages(new UserMessage(prompt))
                        .call()
                        .content();
                if (raw == null) {
                    return null;
                }
                String s = raw.strip().replaceAll("\\s+", " ").replaceAll("^[\"'`]+|[\"'`]+$", "");
                s = s.replaceAll("^(?i)(in|inside|within|at)\\s+\\S*/\\S+?,?\\s+", "")
                        .replaceAll("^\\S+/\\S+\\.[A-Za-z0-9]{1,6}\\s+", "");
                if (!s.isEmpty()) {
                    s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
                }
                if (s.length() > 220) {
                    s = s.substring(0, 217) + "...";
                }
                return s.length() >= 10 ? s : null;
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("429") && attempt < 3) {
                    Thread.sleep(30_000L * attempt);
                    continue;
                }
                return null;
            }
        }
        return null;
    }

    private void save(String repo, String path, String summary, String source) {
        jdbc.update("INSERT INTO file_summaries (repo, file_path, summary, source) VALUES (?,?,?,?) "
                        + "ON CONFLICT (repo, file_path) DO UPDATE SET summary = EXCLUDED.summary, source = EXCLUDED.source",
                repo, path, summary, source);
    }
}