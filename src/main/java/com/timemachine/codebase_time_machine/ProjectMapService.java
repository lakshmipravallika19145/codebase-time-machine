package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectMapService {

    private static final int MAX_LINES = 600;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "build",
            "dist", ".idea", ".gradle", "venv", "__pycache__", "out");

    // Questions about the whole project, not about one piece of code.
    private static final Pattern GLOBAL = Pattern.compile(String.join("|",
                    "\\b(folder|directory|file|project|repo|repository|codebase|code base)\\s+(structure|layout|tree|map|overview)\\b",
                    "\\b(every|each)\\s+(and\\s+(every|each)\\s+)?file\\b",
                    "\\blist\\s+(all\\s+)?(the\\s+)?(project\\s+)?files\\b",
                    "\\b(explain|describe|summari[sz]e)\\s+(me\\s+)?(the\\s+|this\\s+)?(whole\\s+|entire\\s+)?(project|repo|repository|codebase)\\b",
                    "\\bhow is (the |this )?(project|repo|repository|codebase) (organi[sz]ed|structured|laid out)\\b"),
            Pattern.CASE_INSENSITIVE);

    private record FileInfo(String path, long bytes, boolean indexed, int lines, String summary, String source) {}

    private static final class Node {
        final String name;
        final Map<String, Node> children = new TreeMap<>();
        FileInfo file;

        Node(String name) {
            this.name = name;
        }
    }

    private final JdbcTemplate jdbc;

    public ProjectMapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean isGlobalQuestion(String question) {
        return GLOBAL.matcher(question).find();
    }

    // The answer shape used by the chat endpoints.
    public Map<String, Object> answer(String repo) {
        String text;
        try {
            text = render(repo);
        } catch (IOException e) {
            text = "Could not read the project folder: " + e.getMessage();
        }
        return Map.of(
                "answer", text,
                "codeCitations", List.of(),
                "commitCitations", List.of(),
                "rejectedCitations", 0,
                "sources", List.of(),
                "debugRaw", "",
                "kind", "project-map",
                "coverage", "Built directly from the index and the repository folder, not from a search, so it covers the whole project.");
    }

    public String render(String repo) throws IOException {
        List<FileInfo> files = collect(repo);
        if (files.isEmpty()) {
            return "This repository has no files in the index yet. Index it first.";
        }

        Node root = new Node("");
        Set<String> folders = new HashSet<>();
        int ai = 0;
        int rules = 0;
        int none = 0;
        for (FileInfo f : files) {
            String[] parts = f.path().split("/");
            Node cur = root;
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                cur = cur.children.computeIfAbsent(parts[i], Node::new);
                if (i < parts.length - 1) {
                    prefix.append(parts[i]).append('/');
                    folders.add(prefix.toString());
                }
            }
            cur.file = f;
            if (f.summary() == null) {
                none++;
            } else if ("ai".equals(f.source())) {
                ai++;
            } else {
                rules++;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Project map of ").append(repo).append('\n')
                .append(files.size()).append(" files in ").append(folders.size()).append(" folders. ")
                .append("Descriptions: ").append(ai).append(" written by AI (marked [AI]), ")
                .append(rules).append(" from fixed rules, ").append(none).append(" files without one.\n")
                .append("Left out: dependency and build folders (node_modules, target, build, dist, .git).\n\n");

        int[] lines = {0};
        renderChildren(root, 0, out, lines);
        if (lines[0] >= MAX_LINES) {
            out.append("\n... (list cut after ").append(MAX_LINES).append(" lines)\n");
        }
        out.append("\n[AI] descriptions were written once from each file's code and can be imperfect. ")
                .append("Files marked \"not indexed\" are listed but were not read for search.");
        return out.toString();
    }

    // Indexed files come from the database; everything else comes from the repository folder.
    private List<FileInfo> collect(String repo) throws IOException {
        Map<String, FileInfo> byPath = new TreeMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.file_path, max(c.end_line) AS lines, s.summary, s.source "
                        + "FROM code_chunks c LEFT JOIN file_summaries s "
                        + "ON s.repo = c.repo AND s.file_path = c.file_path "
                        + "WHERE c.repo = ? GROUP BY c.file_path, s.summary, s.source", repo);
        for (Map<String, Object> r : rows) {
            String path = (String) r.get("file_path");
            byPath.put(path, new FileInfo(path, 0, true, ((Number) r.get("lines")).intValue(),
                    (String) r.get("summary"), (String) r.get("source")));
        }

        Path root = Path.of("repos", repo);
        if (Files.isDirectory(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    boolean skip = !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString());
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    byPath.computeIfAbsent(rel, p -> new FileInfo(p, attrs.size(), false, 0,
                            attrs.size() == 0 ? "Empty file." : FileRules.describe(p), "rule"));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new ArrayList<>(byPath.values());
    }

    private void renderChildren(Node node, int depth, StringBuilder out, int[] lines) {
        List<Node> kids = new ArrayList<>(node.children.values());
        kids.sort(Comparator.comparing((Node n) -> n.file != null).thenComparing(n -> n.name)); // folders first
        for (Node kid : kids) {
            if (lines[0] >= MAX_LINES) {
                return;
            }
            String indent = "  ".repeat(depth);
            if (kid.file != null) {
                out.append(indent).append(kid.name).append(fileNote(kid.file)).append('\n');
                lines[0]++;
            } else {
                // fold chains like com/example/app/ into one line
                String label = kid.name;
                Node cur = kid;
                while (cur.children.size() == 1) {
                    Node only = cur.children.values().iterator().next();
                    if (only.file != null) {
                        break;
                    }
                    label += "/" + only.name;
                    cur = only;
                }
                out.append(indent).append(label).append("/\n");
                lines[0]++;
                renderChildren(cur, depth + 1, out, lines);
            }
        }
    }

    private static String fileNote(FileInfo f) {
        StringBuilder sb = new StringBuilder("  (");
        if (f.indexed()) {
            sb.append(f.lines()).append(" lines");
        } else {
            sb.append(humanSize(f.bytes())).append(", not indexed");
        }
        sb.append(')');
        if (f.summary() != null) {
            sb.append(" - ").append(f.summary());
            if ("ai".equals(f.source())) {
                sb.append(" [AI]");
            }
        }
        return sb.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}