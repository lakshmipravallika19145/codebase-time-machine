package com.timemachine.codebase_time_machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

@Service
public class AnswerService {

    private static final int TOP_K = 5;
    private static final int MAX_CHUNK_CHARS = 1200;
    private static final String NOT_FOUND = "Not found in the code.";
    // Brackets optional; the file name must have an extension, e.g. OwnerController.java:77-87
    private static final Pattern CITE = Pattern.compile(
            "(?:\\[[ \\t]*)?\\[?([^\\[\\]\\s:`*()\"'<>]+\\.[A-Za-z0-9]{1,6}):(\\d{1,7})-(\\d{1,7})\\]?(?:[ \\t]*\\])?");

    private static final String SYSTEM = """
            You answer questions about a software project using ONLY the code excerpts provided.
            Each excerpt starts with a tag like [path/File.java:10-25] giving its file and line range.
            Rules:
            1. Use only the excerpts. Never use outside knowledge about this project.
            2. After every claim, cite the excerpt it comes from by copying its tag exactly, for example [path/File.java:10-25].
            3. Never invent file names or line numbers. Only cite tags that appear in the excerpts.
            4. If the excerpts do not answer the question, reply exactly: Not found in the code.
            5. Be concise: a short explanation, then where it is in the code.
            6. Write tags with plain ASCII characters only. Use a normal hyphen "-" between line numbers, never a non-breaking hyphen or en dash.
            7. Always write citations in plain square brackets [ ]. Never use other bracket styles such as fullwidth or lenticular brackets.
            """;

    private final SearchService search;
    private final ChatClient chatClient;

    public AnswerService(SearchService search, ChatClient.Builder builder) {
        this.search = search;
        this.chatClient = builder.build();
    }

    public Map<String, Object> answer(String repo, String question) {
        List<Hit> hits = search.hybrid(repo, question, TOP_K);
        if (hits.isEmpty()) {
            return result(NOT_FOUND, List.of(), 0, hits, "");
        }

        String raw;
        try {
            raw = chatClient.prompt()
                    .system(SYSTEM)
                    .messages(new UserMessage(buildPrompt(question, hits)))
                    .call()
                    .content();
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("429")) {
                return result("The AI service is rate-limited right now. Wait a minute and try again.",
                        List.of(), 0, hits, "");
            }
            throw e;
        }
        if (raw == null) {
            raw = "";
        }
        raw = normalize(raw);

        // The teacher: keep only citations that point at real excerpts we sent.
        Matcher m = CITE.matcher(raw);
        StringBuilder cleaned = new StringBuilder();
        Map<String, Map<String, Object>> cites = new LinkedHashMap<>();
        int rejected = 0;
        while (m.find()) {
            int start = Integer.parseInt(m.group(2));
            int end = Integer.parseInt(m.group(3));
            Hit hit = findHit(hits, m.group(1), start, end);
            if (hit == null) {
                rejected++;
                m.appendReplacement(cleaned, "");
            } else {
                String tag = "[" + hit.filePath() + ":" + start + "-" + end + "]";
                cites.putIfAbsent(tag, Map.of("file", hit.filePath(), "startLine", start, "endLine", end));
                m.appendReplacement(cleaned, Matcher.quoteReplacement(tag));
            }
        }
        m.appendTail(cleaned);

        String finalAnswer = cleaned.toString().trim();
        if (cites.isEmpty()) {
            finalAnswer = raw.contains(NOT_FOUND)
                    ? NOT_FOUND
                    : "I couldn't ground an answer in the code, so I won't guess.";
        }
        return result(finalAnswer, cites.values(), rejected, hits, raw);
    }

    // Models sometimes write special dashes, spaces and brackets; turn them into plain ones.
    private static String normalize(String s) {
        return s.replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("[\\u00A0\\u2007\\u202F]", " ")
                .replaceAll("[\\u3010\\u3014\\uFF3B]", "[")
                .replaceAll("[\\u3011\\u3015\\uFF3D]", "]");
    }

    private Hit findHit(List<Hit> hits, String file, int start, int end) {
        return hits.stream()
                .filter(h -> h.filePath().equals(file) || h.filePath().endsWith("/" + file))
                .filter(h -> start <= end && start >= h.startLine() && end <= h.endLine())
                .findFirst()
                .orElse(null);
    }

    private String buildPrompt(String question, List<Hit> hits) {
        StringBuilder sb = new StringBuilder("Question: ").append(question).append("\n\nExcerpts:\n");
        for (Hit h : hits) {
            String c = h.content().length() > MAX_CHUNK_CHARS
                    ? h.content().substring(0, MAX_CHUNK_CHARS) : h.content();
            sb.append("\n[").append(h.filePath()).append(':')
                    .append(h.startLine()).append('-').append(h.endLine()).append("]\n")
                    .append(c).append('\n');
        }
        return sb.toString();
    }

    // debugRaw is temporary, for debugging only. We'll remove it before deployment.
    private Map<String, Object> result(String answer, Collection<Map<String, Object>> cites,
                                       int rejected, List<Hit> hits, String raw) {
        return Map.of(
                "answer", answer,
                "citations", new ArrayList<>(cites),
                "rejectedCitations", rejected,
                "sources", hits.stream()
                        .map(h -> h.filePath() + ":" + h.startLine() + "-" + h.endLine())
                        .toList(),
                "debugRaw", raw);
    }
}