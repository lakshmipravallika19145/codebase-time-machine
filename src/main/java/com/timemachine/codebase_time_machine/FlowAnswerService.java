package com.timemachine.codebase_time_machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FlowAnswerService {

    private static final int MAX_STAGES = 8;
    private static final int MIN_STAGES = 2;
    private static final int PLAN_LIST_CHARS = 16_000;
    private static final int SUMMARY_CHARS = 90;
    private static final int CHUNK_CHARS = 600;
    private static final int MAX_EXCERPTS_PER_STAGE = 7;
    private static final int PER_FILE = 2;
    private static final long STAGE_PAUSE_MS = 15_000; // keeps us under the free tier's tokens-per-minute limit
    private static final String NOT_COVERED = "Not covered by the retrieved code.";

    // Questions about a whole flow or the architecture, not about one piece of code.
    private static final Pattern FLOW = Pattern.compile(String.join("|",
            "\\barchitecture\\b",
            "\\b(end[- ]to[- ]end|work ?flow|data ?flow|life ?cycle|walk ?through|step[- ]by[- ]step)\\b",
            "\\bfrom\\s+[\\w' -]{2,40}?\\s+(to|until|till)\\s+[\\w' -]{2,40}"),
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_CITE = Pattern.compile(
            "(?:\\[[ \\t]*)?\\[?([^\\[\\]\\s:`*()\"'<>]+\\.[A-Za-z0-9]{1,6}):(\\d{1,7})-(\\d{1,7})\\]?(?:[ \\t]*\\])?");

    private static final String PLAN_SYSTEM = """
            You plan how to explain a flow or the architecture of a software project, step by step.
            You are given the user's question, an overview of the project, and a list of files with one-line descriptions.
            Choose 5 to 8 stages in the order the flow happens. For a question like "from login to live auction", go from login all the way to the live auction, and cover every role involved (for example the person who creates the auction and the person who bids).
            For each stage output exactly one line in this format:
            STAGE | short title | short search query | file path 1; file path 2; file path 3
            Rules:
            1. Use only file paths from the list, copied exactly. Choose 2 to 4 files per stage.
            2. For every action a user takes in the interface, include BOTH the frontend file that triggers it AND the backend file that handles it (controller, message handler or service), plus the security or configuration file that protects it when one exists.
            3. The search query describes what happens at that stage in plain words, without file names.
            4. Output only the STAGE lines, nothing else.
            """;

    private static final String STAGE_SYSTEM = """
            You explain ONE stage of a larger flow in a software project, using ONLY the code excerpts provided.
            Each excerpt starts with a tag like [path/File.java:10-25] (file and line range).
            Write 2 to 4 sentences in plain words: what happens at this stage and which code does it.
            Rules:
            1. Use only the excerpts. Never use outside knowledge about this project.
            2. Cite every claim with the exact tag of the excerpt it comes from, for example [path/File.java:10-25]. Never invent tags, file names or line numbers.
            3. If the excerpts do not show this stage, reply exactly: Not covered by the retrieved code.
            4. Write tags in plain ASCII with plain square brackets [ ] and a normal hyphen "-" between line numbers.
            """;

    record Stage(String title, String query, List<String> files) {}

    private final SearchService search;
    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final String stageModel;

    public FlowAnswerService(SearchService search, JdbcTemplate jdbc, ChatClient.Builder builder,
                             @Value("${app.flow.stage-model:openai/gpt-oss-20b}") String stageModel) {
        this.search = search;
        this.jdbc = jdbc;
        this.chatClient = builder.build();
        this.stageModel = stageModel;
    }

    public static boolean isFlowQuestion(String question) {
        return FLOW.matcher(question).find();
    }

    // Returns null when no plan can be made; the caller then uses the normal path.
    public Map<String, Object> answer(String repo, String question) {
        try {
            return run(repo, question);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Map<String, Object> run(String repo, String question) throws InterruptedException {
        List<Map<String, Object>> files = jdbc.queryForList(
                "SELECT file_path, summary FROM file_summaries WHERE repo = ? AND source = 'ai' ORDER BY file_path",
                repo);
        if (files.size() < 5) {
            return null; // no file descriptions yet
        }

        // 1. Plan the stages from the project map.
        Set<String> known = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> f : files) {
            String path = (String) f.get("file_path");
            known.add(path);
            String s = (String) f.get("summary");
            if (s.length() > SUMMARY_CHARS) {
                s = s.substring(0, SUMMARY_CHARS) + "...";
            }
            lines.add("- " + path + ": " + s + "\n");
        }
        lines = sample(lines, PLAN_LIST_CHARS);

        List<String> ov = jdbc.queryForList(
                "SELECT overview FROM project_overviews WHERE repo = ?", String.class, repo);
        StringBuilder planPrompt = new StringBuilder("Question: ").append(question)
                .append("\n\nProject overview: ").append(ov.isEmpty() ? "(none)" : ov.get(0))
                .append("\n\nFiles:\n");
        lines.forEach(planPrompt::append);

        String planRaw = ask(PLAN_SYSTEM, planPrompt.toString(), null);
        if (planRaw == null) {
            return null;
        }
        List<Stage> stages = parsePlan(planRaw, known);
        if (stages.size() < MIN_STAGES) {
            return null;
        }

        // 2. Retrieve and write each stage.
        Map<Long, Hit> allHits = new LinkedHashMap<>();
        List<List<Hit>> stageHits = new ArrayList<>();
        List<String> stageRaw = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            Stage st = stages.get(i);
            List<Hit> hits = gather(repo, st);
            hits.forEach(h -> allHits.putIfAbsent(h.id(), h));
            stageHits.add(hits);
            stageRaw.add(hits.isEmpty() ? null : ask(STAGE_SYSTEM, stagePrompt(question, st, hits), stageModel));
            if (i < stages.size() - 1) {
                Thread.sleep(STAGE_PAUSE_MS);
            }
        }

        // 3. Verify every citation and assemble the answer.
        int[] rejected = {0};
        Map<String, Map<String, Object>> cites = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        int covered = 0;
        for (int i = 0; i < stages.size(); i++) {
            String raw = stageRaw.get(i);
            String text;
            if (raw == null) {
                text = NOT_COVERED + (stageHits.get(i).isEmpty()
                        ? " (nothing relevant was retrieved)" : " (the AI service was busy)");
            } else {
                int[] accepted = {0};
                text = verify(raw, stageHits.get(i), cites, rejected, accepted).strip();
                if (accepted[0] == 0 && !text.contains(NOT_COVERED)) {
                    text = NOT_COVERED + " (no verifiable citation)";
                }
            }
            if (!text.contains(NOT_COVERED)) {
                covered++;
            }
            out.append("**").append(i + 1).append(". ").append(stages.get(i).title()).append("**\n")
                    .append(text).append("\n\n");
        }
        if (covered == 0) {
            return null;
        }

        long fileCount = allHits.values().stream().map(Hit::filePath).distinct().count();

        // Files the plan chose but the answer never cited, so a gap is visible instead of hidden.
        Set<String> citedFiles = new HashSet<>();
        cites.values().forEach(c -> citedFiles.add((String) c.get("file")));
        Set<String> plannedButUncited = new java.util.LinkedHashSet<>();
        for (Stage st : stages) {
            for (String f : st.files()) {
                if (!citedFiles.contains(f)) {
                    plannedButUncited.add(f.substring(f.lastIndexOf('/') + 1));
                }
            }
        }
        String coverage = "Walked through " + stages.size() + " steps (" + covered + " with verified code) using "
                + allHits.size() + " excerpts from " + fileCount + " files. The AI chose which files to look at "
                + "from the project map, so the walk-through can miss parts. Steps marked not covered had no verifiable code."
                + (plannedButUncited.isEmpty() ? ""
                        : " Files the plan chose but the answer did not cite: " + String.join(", ", plannedButUncited) + ".");
        return Map.of(
                "answer", out.toString().strip(),
                "codeCitations", List.copyOf(cites.values()),
                "commitCitations", List.of(),
                "rejectedCitations", rejected[0],
                "sources", allHits.values().stream()
                        .map(h -> h.filePath() + ":" + h.startLine() + "-" + h.endLine()).toList(),
                "coverage", coverage,
                "kind", "flow",
                "debugRaw", planRaw);
    }

    // Parses lines like "STAGE | title | query | path1; path2". Unknown file paths are dropped.
    static List<Stage> parsePlan(String raw, Set<String> known) {
        List<Stage> stages = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String t = line.strip().replaceAll("^[-*\u2022\\d.\\s]+", "");
            if (!t.regionMatches(true, 0, "STAGE", 0, 5)) {
                continue;
            }
            String[] parts = t.split("\\|", 4);
            if (parts.length < 3) {
                continue;
            }
            String title = parts[1].strip();
            String query = parts[2].strip();
            List<String> files = new ArrayList<>();
            if (parts.length == 4) {
                for (String f : parts[3].split("[;,]")) {
                    String p = f.strip().replaceAll("^[`*\\s]+|[`*\\s]+$", "");
                    if (known.contains(p)) {
                        files.add(p);
                    }
                }
            }
            if (!title.isEmpty() && query.length() >= 4 && stages.size() < MAX_STAGES) {
                stages.add(new Stage(title, query, files));
            }
        }
        return stages;
    }

    // Best chunks inside the planned files, plus the best chunks across the whole repo.
    // The best chunks of every planned file (so each chosen file is represented), plus the best across the repo.
    private List<Hit> gather(String repo, Stage st) {
        List<Hit> hits = new ArrayList<>(search.perFile(repo, st.query(), st.files(), PER_FILE));
        for (Hit h : search.hybrid(repo, st.query(), 3)) {
            if (hits.stream().noneMatch(x -> x.id() == h.id())) {
                hits.add(h);
            }
        }
        return hits.size() > MAX_EXCERPTS_PER_STAGE ? new ArrayList<>(hits.subList(0, MAX_EXCERPTS_PER_STAGE)) : hits;
    }

    private String stagePrompt(String question, Stage st, List<Hit> hits) {
        StringBuilder sb = new StringBuilder("Overall question: ").append(question)
                .append("\nStage to explain: ").append(st.title()).append("\n\nExcerpts:\n");
        for (Hit h : hits) {
            String c = h.content().length() > CHUNK_CHARS ? h.content().substring(0, CHUNK_CHARS) : h.content();
            sb.append("\n[").append(h.filePath()).append(':').append(h.startLine()).append('-')
                    .append(h.endLine()).append("]\n").append(c).append('\n');
        }
        return sb.toString();
    }

    private String ask(String system, String prompt, String modelName) throws InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ChatClient.ChatClientRequestSpec spec = chatClient.prompt().system(system);
                if (modelName != null) {
                    spec = spec.options(ChatOptions.builder().model(modelName));
                }
                String raw = spec.messages(new UserMessage(prompt)).call().content();
                return raw == null ? null : normalize(raw);
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

    private String verify(String text, List<Hit> hits, Map<String, Map<String, Object>> cites,
                          int[] rejected, int[] accepted) {
        Matcher m = CODE_CITE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int start = Integer.parseInt(m.group(2));
            int end = Integer.parseInt(m.group(3));
            Hit hit = findHit(hits, m.group(1), start, end);
            if (hit == null) {
                rejected[0]++;
                m.appendReplacement(sb, "");
            } else {
                accepted[0]++;
                String tag = "[" + hit.filePath() + ":" + start + "-" + end + "]";
                cites.putIfAbsent(tag, Map.of("file", hit.filePath(), "startLine", start, "endLine", end));
                m.appendReplacement(sb, Matcher.quoteReplacement(tag));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Hit findHit(List<Hit> hits, String file, int start, int end) {
        return hits.stream()
                .filter(h -> h.filePath().equals(file) || h.filePath().endsWith("/" + file))
                .filter(h -> start <= end && start >= h.startLine() && end <= h.endLine())
                .findFirst()
                .orElse(null);
    }

    // Keeps an even spread of lines when the list is too long, instead of cutting off the end.
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

    // Models sometimes write special dashes, spaces and brackets; turn them into plain ones.
    private static String normalize(String s) {
        return s.replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("[\\u00A0\\u2007\\u202F]", " ")
                .replaceAll("[\\u3010\\u3014\\uFF3B]", "[")
                .replaceAll("[\\u3011\\u3015\\uFF3D]", "]");
    }
}
