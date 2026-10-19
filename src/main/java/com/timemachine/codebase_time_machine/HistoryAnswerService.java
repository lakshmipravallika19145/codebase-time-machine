package com.timemachine.codebase_time_machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

@Service
public class HistoryAnswerService {

    private static final int TOP_K = 8;
    private static final int LIST_ALL = 12;
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_FILES_CHARS = 400;
    private static final String NOT_FOUND = "Not found in the commit history.";

    // Matches [commit:abc1234] and also strips a stray outer wrapper like "[ ... ]",
    // but never touches line breaks or the spaces around the citation.
    private static final Pattern CITE = Pattern.compile(
            "(?:\\[[ \\t]*)?\\[?commit:([0-9a-fA-F]{7,40})\\]?(?:[ \\t]*\\])?");

    // Words that usually signal an explicit reason in a commit message.
    private static final Pattern REASON = Pattern.compile(
            "(?i)(\\bbecause\\b|\\bso that\\b|\\bin order to\\b|\\bdue to\\b|\\bsince\\b"
                    + "|\\bto (fix|avoid|prevent|allow|enable|support|improve|ensure|reduce|resolve)\\b"
                    + "|\\bneeded\\b|\\botherwise\\b|#\\d+)");

    private static final Set<String> GENERIC = Set.of("fix", "fixes", "fixed", "update", "updates",
            "wip", "change", "changes", "misc", "test", "tests", "commit", "initial commit",
            "minor fixes", "minor changes", "cleanup", "refactor", "done", "work in progress");

    private static final String SYSTEM = """
            You answer questions about a software project's git history using ONLY the commit excerpts provided.
            Each excerpt starts with a tag like [commit:abc1234], then the date, author and size, then the commit message and the files changed.
            Rules:
            1. Use only the excerpts. Never use outside knowledge about this project.
            2. After every claim, cite its commit by copying the tag exactly, for example [commit:abc1234]. Only cite tags that appear in the excerpts.
            3. WHAT changed means what the files and the message show. WHY means an explicit reason written in the message, such as "because ...", "so that ...", "to fix ...", or an issue link. A message that only describes the change (for example "Add X" or "Milestone 3: clone repos") is WHAT, never WHY.
            4. For every commit you describe, give the WHAT. Give a WHY only if the message states one, quoting or closely paraphrasing it. Otherwise write: Why: not recorded in the commit message. If an excerpt has a Note saying no reason is given, you must write that.
            5. Never guess or invent motives.
            6. If the excerpts do not answer the question, reply exactly: Not found in the commit history.
            7. Be concise. When asked for a list of commits, list them newest first, one short block per commit.
            8. Write tags in plain ASCII with plain square brackets [ ]. Never use other bracket styles. Put each citation right after the claim it supports.
            """;

    private final HistorySearchService search;
    private final ChatClient chatClient;

    public HistoryAnswerService(HistorySearchService search, ChatClient.Builder builder) {
        this.search = search;
        this.chatClient = builder.build();
    }

    public Map<String, Object> answer(String repo, String question,
                                      HistorySearchService.Filters f, boolean recentMode) {
        List<CommitHit> hits = search.pick(repo, question, f, TOP_K, LIST_ALL, recentMode);
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

        // The teacher: keep only citations that point at commits we really sent.
        Matcher m = CITE.matcher(raw);
        StringBuilder cleaned = new StringBuilder();
        Map<String, Map<String, Object>> cites = new LinkedHashMap<>();
        int rejected = 0;
        while (m.find()) {
            String wanted = m.group(1).toLowerCase();
            CommitHit hit = hits.stream()
                    .filter(h -> h.hash().startsWith(wanted))
                    .findFirst()
                    .orElse(null);
            if (hit == null) {
                rejected++;
                m.appendReplacement(cleaned, "");
            } else {
                String tag = "[commit:" + shortHash(hit) + "]";
                cites.putIfAbsent(tag, Map.of(
                        "commit", shortHash(hit),
                        "date", hit.committedAt().substring(0, 10),
                        "author", String.valueOf(hit.author()),
                        "subject", cap(firstLine(hit.message()), 100)));
                m.appendReplacement(cleaned, Matcher.quoteReplacement(tag));
            }
        }
        m.appendTail(cleaned);

        String finalAnswer = cleaned.toString().trim();
        if (cites.isEmpty()) {
            finalAnswer = raw.contains(NOT_FOUND)
                    ? NOT_FOUND
                    : "I couldn't ground an answer in the commit history, so I won't guess.";
        }
        return result(finalAnswer, cites.values(), rejected, hits, raw);
    }

    private String buildPrompt(String question, List<CommitHit> hits) {
        StringBuilder sb = new StringBuilder("Question: ").append(question).append("\n\nCommits:\n");
        for (CommitHit h : hits) {
            sb.append("\n[commit:").append(shortHash(h)).append("] ")
                    .append(h.committedAt(), 0, 10).append(" | ").append(h.author())
                    .append(" | +").append(h.additions()).append(" -").append(h.deletions()).append('\n')
                    .append("Message: ").append(cap(h.message(), MAX_MESSAGE_CHARS)).append('\n');
            if (lacksReason(h.message())) {
                sb.append("(Note: this commit message describes what was done but gives no explicit reason, "
                        + "so the reason is not recorded.)\n");
            }
            sb.append("Files:\n").append(capLines(h.files(), MAX_FILES_CHARS)).append('\n');
        }
        return sb.toString();
    }

    // True if the message is too short or generic to explain anything, or has no reason words.
    static boolean lacksReason(String message) {
        String m = message.strip().toLowerCase();
        int words = m.isEmpty() ? 0 : m.split("\\s+").length;
        return words < 3 || GENERIC.contains(m) || !REASON.matcher(message).find();
    }

    private static String shortHash(CommitHit h) {
        return h.hash().substring(0, 7);
    }

    private static String firstLine(String s) {
        return s.strip().split("\\R", 2)[0];
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // Cuts at a line boundary so a file name is never chopped in half.
    private static String capLines(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf('\n', max);
        return (cut > 0 ? s.substring(0, cut) : s.substring(0, max)) + "\n... (more files not shown)";
    }

    // Models sometimes write special dashes, spaces and brackets; turn them into plain ones.
    private static String normalize(String s) {
        return s.replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("[\\u00A0\\u2007\\u202F]", " ")
                .replaceAll("[\\u3010\\u3014\\uFF3B]", "[")
                .replaceAll("[\\u3011\\u3015\\uFF3D]", "]");
    }

    // debugRaw is temporary, for debugging only. We'll remove it before deployment.
    private Map<String, Object> result(String answer, Collection<Map<String, Object>> cites,
                                       int rejected, List<CommitHit> hits, String raw) {
        return Map.of(
                "answer", answer,
                "citations", new ArrayList<>(cites),
                "rejectedCitations", rejected,
                "sources", hits.stream().map(HistoryAnswerService::shortHash).toList(),
                "debugRaw", raw);
    }
}