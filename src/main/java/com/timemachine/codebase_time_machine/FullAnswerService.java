package com.timemachine.codebase_time_machine;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class FullAnswerService {

    private static final int TOP_K = 6;
    private static final int COMMITS_PER_CHUNK = 2;
    private static final int MAX_CHUNK_CHARS = 700;
    private static final int MAX_MESSAGE_CHARS = 250;
    private static final String NOT_FOUND = "Not found in the code.";

    private static final Pattern CODE_CITE = Pattern.compile(
            "(?:\\[[ \\t]*)?\\[?([^\\[\\]\\s:`*()\"'<>]+\\.[A-Za-z0-9]{1,6}):(\\d{1,7})-(\\d{1,7})\\]?(?:[ \\t]*\\])?");
    private static final Pattern COMMIT_CITE = Pattern.compile(
            "(?:\\[[ \\t]*)?\\[?commit:([0-9a-fA-F]{7,40})\\]?(?:[ \\t]*\\])?");

    private static final String SYSTEM = """
            You answer questions about a software project using ONLY the code excerpts and the commit history shown with them.
            Each excerpt starts with a tag like [path/File.java:10-25] (file and line range), then the code, then "History of these lines": the commits that last changed those lines, each with a tag like [commit:abc1234], its date, author, how many of the excerpt's lines it last changed, and its commit message.
            Answer in this shape:
            Where: the file and line range, citing the code tag.
            What it does: a short explanation of the code, citing the code tag.
            Why it exists: only what the commit messages state, citing the commit tag.
            Rules:
            1. Use only the excerpts and the commits shown. Never use outside knowledge about this project.
            2. Cite code with its exact tag, for example [path/File.java:10-25], and commits with their exact tag, for example [commit:abc1234]. Never invent tags, file names or line numbers.
            3. WHY must come from an explicit reason in a commit message, such as "because ...", "so that ...", "to fix ...", or an issue link. A message that only describes the change is WHAT, never WHY.
            4. If no commit message states a reason, write: Why: not recorded in the commit messages. If a commit has a Note saying no reason is given, you must say so.
            5. If the excerpts do not answer the question, reply exactly: Not found in the code.
            6. Be concise. Write tags in plain ASCII with plain square brackets [ ] and a normal hyphen "-" between line numbers.
            7. Choose the excerpt that most directly answers the question. If the question is about creating or adding something, do not answer from an update or edit excerpt (and the other way round). If several excerpts are relevant, give a separate Where line for each.
            """;

    private record BlameInfo(String hash, String author, String committedAt, String message, int lines) {
    }

    private static final RowMapper<BlameInfo> BLAME_MAPPER = (rs, i) -> new BlameInfo(
            rs.getString("hash"), rs.getString("author"),
            rs.getTimestamp("committed_at").toInstant().toString(),
            rs.getString("message"), rs.getInt("lines"));

    private final SearchService search;
    private final JdbcTemplate jdbc;
    private final ProjectMapService projectMap;
    private final FlowAnswerService flow;
    private final ChatClient chatClient;

    public FullAnswerService(SearchService search, JdbcTemplate jdbc, ProjectMapService projectMap,
                             FlowAnswerService flow, ChatClient.Builder builder) {
        this.search = search;
        this.jdbc = jdbc;
        this.projectMap = projectMap;
        this.flow = flow;
        this.chatClient = builder.build();
    }

    public Map<String, Object> answer(String repo, String question) {
        // Questions about a whole flow need several searches, not one.
        if (FlowAnswerService.isFlowQuestion(question)) {
            Map<String, Object> walk = flow.answer(repo, question);
            if (walk != null) {
                return walk;
            }
        }
        // Questions about the whole project cannot be answered from a few retrieved excerpts.
        if (ProjectMapService.isGlobalQuestion(question)) {
            return projectMap.answer(repo);
        }
        Integer blameRows = jdbc.queryForObject(
                "SELECT count(*) FROM chunk_blame WHERE repo = ?", Integer.class, repo);
        if (blameRows == null || blameRows == 0) {
            return result("No history data for this repo yet. Run /index-blame first.",
                    List.of(), List.of(), 0, List.of(), "");
        }

        List<Hit> hits = search.hybrid(repo, retrievalQuery(question), TOP_K);
        if (hits.isEmpty()) {
            return result(NOT_FOUND, List.of(), List.of(), 0, hits, "");
        }

        Map<Long, List<BlameInfo>> blameByChunk = new HashMap<>();
        Map<String, BlameInfo> shown = new LinkedHashMap<>();
        for (Hit h : hits) {
            List<BlameInfo> list = jdbc.query(
                    "SELECT hash, author, committed_at, message, lines FROM chunk_blame "
                            + "WHERE chunk_id = ? ORDER BY lines DESC, committed_at DESC LIMIT ?",
                    BLAME_MAPPER, h.id(), COMMITS_PER_CHUNK);
            blameByChunk.put(h.id(), list);
            for (BlameInfo b : list) {
                shown.putIfAbsent(b.hash(), b);
            }
        }

        String raw;
        try {
            raw = chatClient.prompt()
                    .system(SYSTEM)
                    .messages(new UserMessage(buildPrompt(question, hits, blameByChunk)))
                    .call()
                    .content();
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("429")) {
                return result("The AI service is rate-limited right now. Wait a minute and try again.",
                        List.of(), List.of(), 0, hits, "");
            }
            throw e;
        }
        if (raw == null) {
            raw = "";
        }
        raw = normalize(raw);

        // The teacher: keep only citations that point at excerpts and commits we really sent.
        int[] rejected = {0};
        Map<String, Map<String, Object>> commitCites = new LinkedHashMap<>();
        Map<String, Map<String, Object>> codeCites = new LinkedHashMap<>();
        String text = checkCommits(raw, shown, commitCites, rejected);
        text = checkCode(text, hits, codeCites, rejected).trim();

        if (codeCites.isEmpty() && commitCites.isEmpty()) {
            text = raw.contains(NOT_FOUND)
                    ? NOT_FOUND
                    : "I couldn't ground an answer in the code, so I won't guess.";
        }
        return result(text, codeCites.values(), commitCites.values(), rejected[0], hits, raw);
    }

    private String checkCommits(String text, Map<String, BlameInfo> shown,
                                Map<String, Map<String, Object>> cites, int[] rejected) {
        Matcher m = COMMIT_CITE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String wanted = m.group(1).toLowerCase();
            BlameInfo b = shown.values().stream()
                    .filter(x -> x.hash().startsWith(wanted))
                    .findFirst()
                    .orElse(null);
            if (b == null) {
                rejected[0]++;
                m.appendReplacement(sb, "");
            } else {
                String tag = "[commit:" + b.hash().substring(0, 7) + "]";
                cites.putIfAbsent(tag, Map.of(
                        "commit", b.hash().substring(0, 7),
                        "date", b.committedAt().substring(0, 10),
                        "author", String.valueOf(b.author()),
                        "subject", cap(firstLine(b.message()), 100)));
                m.appendReplacement(sb, Matcher.quoteReplacement(tag));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String checkCode(String text, List<Hit> hits,
                             Map<String, Map<String, Object>> cites, int[] rejected) {
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

    private String buildPrompt(String question, List<Hit> hits, Map<Long, List<BlameInfo>> blame) {
        StringBuilder sb = new StringBuilder("Question: ").append(question).append("\n\nExcerpts:\n");
        for (Hit h : hits) {
            sb.append("\n[").append(h.filePath()).append(':')
                    .append(h.startLine()).append('-').append(h.endLine()).append("]\n")
                    .append(cap(h.content(), MAX_CHUNK_CHARS)).append("\nHistory of these lines:\n");
            List<BlameInfo> list = blame.getOrDefault(h.id(), List.of());
            if (list.isEmpty()) {
                sb.append("- (no history found)\n");
            }
            for (BlameInfo b : list) {
                sb.append("- [commit:").append(b.hash(), 0, 7).append("] ")
                        .append(b.committedAt(), 0, 10).append(" | ").append(b.author())
                        .append(" | last changed ").append(b.lines()).append(" of these lines\n")
                        .append("  Message: ").append(cap(b.message(), MAX_MESSAGE_CHARS)).append('\n');
                if (HistoryAnswerService.lacksReason(b.message())) {
                    sb.append("  (Note: this commit message describes what was done but gives no explicit reason, "
                            + "so the reason is not recorded.)\n");
                }
            }
        }
        return sb.toString();
    }

    // The "why ..." part of a question is answered from history, so don't let it steer the code search.
    static String retrievalQuery(String question) {
        String q = question.replaceAll("(?i)[,;]?\\s*(and\\s+)?(why|for what reason|what was the reason)\\b.*$", "").strip();
        return q.length() >= 8 ? q : question;
    }

    private static String firstLine(String s) {
        return s.strip().split("\\R", 2)[0];
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // Models sometimes write special dashes, spaces and brackets; turn them into plain ones.
    private static String normalize(String s) {
        return s.replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("[\\u00A0\\u2007\\u202F]", " ")
                .replaceAll("[\\u3010\\u3014\\uFF3B]", "[")
                .replaceAll("[\\u3011\\u3015\\uFF3D]", "]");
    }

    // debugRaw is temporary, for debugging only. We'll remove it before deployment.
    // debugRaw is temporary, for debugging only. It is removed from public responses.
    private Map<String, Object> result(String answer, Collection<Map<String, Object>> codeCites,
                                       Collection<Map<String, Object>> commitCites, int rejected,
                                       List<Hit> hits, String raw) {
        return Map.of(
                "answer", answer,
                "codeCitations", List.copyOf(codeCites),
                "commitCitations", List.copyOf(commitCites),
                "rejectedCitations", rejected,
                "sources", hits.stream()
                        .map(h -> h.filePath() + ":" + h.startLine() + "-" + h.endLine())
                        .toList(),
                "coverage", coverage(hits),
                "debugRaw", raw);
    }

    private static String coverage(List<Hit> hits) {
        if (hits.isEmpty()) {
            return "No code excerpts were retrieved.";
        }
        long files = hits.stream().map(Hit::filePath).distinct().count();
        return "Based on " + hits.size() + " retrieved excerpts from " + files
                + " files. This is not a full scan of the repository.";
    }
}