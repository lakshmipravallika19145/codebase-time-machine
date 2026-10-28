package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OverviewService {

    private static final int README_CHARS = 3000;
    private static final int DESCRIPTIONS_CHARS = 11_000;
    private static final int SUMMARY_CHARS = 110;

    private static final String SYSTEM = """
            You write a short overview of a software project for a beginner.
            You are given the repository name, an excerpt of the README, and one-line descriptions of the project's files.
            Write ONE paragraph of at most 5 sentences covering: what the project is for, how it is organized (for example backend and frontend), the main technologies, and the main things users can do.
            Rules:
            1. Use only the material provided. Do not add features, technologies or claims that are not in it.
            2. Name a technology only if it appears in the README or the file descriptions.
            3. Mention an architectural layer (for example "services") only if a file description or the README names it.
            4. Do not generalize: if the material only mentions creating something, do not say it can also be updated or deleted.
            5. If the material is unclear about something, leave it out instead of guessing.
            6. Plain words, no marketing language, no bullet points, no headings.
            7. Output only the paragraph.
            """;

    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final String model;

    public OverviewService(JdbcTemplate jdbc, ChatClient.Builder builder,
                           @Value("${app.summary.model:openai/gpt-oss-20b}") String model) {
        this.jdbc = jdbc;
        this.chatClient = builder.build();
        this.model = model;
    }

    public Map<String, Object> generate(String repo) throws InterruptedException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT file_path, summary, source FROM file_summaries WHERE repo = ? ORDER BY file_path", repo);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No file descriptions yet. Run /index-summaries first.");
        }

        // One compact line per useful file; fixed-rule lines (lock files, images...) add nothing.
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String path = (String) r.get("file_path");
            boolean keep = "ai".equals(r.get("source"))
                    || path.endsWith("pom.xml") || path.endsWith("package.json")
                    || path.endsWith("Dockerfile") || path.endsWith("docker-compose.yml")
                    || path.endsWith("application.yml") || path.endsWith("application.properties");
            if (!keep) {
                continue;
            }
            String summary = (String) r.get("summary");
            if (summary.length() > SUMMARY_CHARS) {
                summary = summary.substring(0, SUMMARY_CHARS) + "...";
            }
            lines.add("- " + shortPath(path) + ": " + summary + "\n");
        }
        lines = sample(lines, DESCRIPTIONS_CHARS);

        String readme = readme(repo);
        StringBuilder prompt = new StringBuilder("Repository name: ").append(repo).append("\n\n");
        prompt.append(readme.isEmpty() ? "README: (none found)\n" : "README excerpt:\n" + readme + "\n");
        prompt.append("\nFile descriptions (written by AI from each file's code):\n");
        lines.forEach(prompt::append);

        String overview = ask(prompt.toString());
        if (overview == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service did not return an overview. Wait a minute and try again.");
        }
        String basis = (readme.isEmpty() ? "" : "the root README, ") + lines.size() + " file descriptions";
        jdbc.update("INSERT INTO project_overviews (repo, overview, basis) VALUES (?,?,?) "
                + "ON CONFLICT (repo) DO UPDATE SET overview = EXCLUDED.overview, basis = EXCLUDED.basis, "
                + "created_at = now()", repo, overview, basis);
        return Map.of("repo", repo, "overview", overview, "basis", basis);
    }

    private String ask(String prompt) throws InterruptedException {
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
                if (s.length() > 1000) {
                    s = s.substring(0, 997) + "...";
                }
                return s.length() >= 80 ? s : null;
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

    private String readme(String repo) {
        Path dir = Path.of("repos", repo);
        for (String name : List.of("README.md", "readme.md", "Readme.md", "README")) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    String text = Files.readString(p);
                    return text.length() > README_CHARS ? text.substring(0, README_CHARS) : text;
                } catch (IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    // the last three path parts are enough to tell files apart
    private static String shortPath(String path) {
        String[] parts = path.split("/");
        int from = Math.max(0, parts.length - 3);
        return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    // If there is too much text, keep an even spread of lines instead of cutting off the end.
    private static List<String> sample(List<String> lines, int maxChars) {
        int total = lines.stream().mapToInt(String::length).sum();
        if (total <= maxChars) {
            return lines;
        }
        double keep = (double) maxChars / total;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (Math.floor((i + 1) * keep) > Math.floor(i * keep)) {
                out.add(lines.get(i));
            }
        }
        return out;
    }
}