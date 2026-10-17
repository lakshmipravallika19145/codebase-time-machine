package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

@Service
public class CodeChunker {

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target",
            "build", "dist", ".idea", ".mvn", ".gradle", "venv", "__pycache__", "out");
    private static final Set<String> EXTENSIONS = Set.of("java", "py", "js", "jsx", "ts",
            "tsx", "sql", "xml", "properties", "yml", "yaml", "md", "html", "css", "gradle");
    private static final long MAX_FILE_BYTES = 200_000;
    private static final int BLOCK_LINES = 40;
    private static final int METHOD_MAX_LINES = 80;
    private static final int OVERLAP = 10;

    private final JavaParser javaParser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public List<Chunk> chunkRepo(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                boolean skip = !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString());
                return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() <= MAX_FILE_BYTES && EXTENSIONS.contains(extension(file))) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<Chunk> chunks = new ArrayList<>();
        for (Path file : files) {
            chunks.addAll(chunkFile(root, file));
        }
        return chunks;
    }

    private String extension(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase();
    }

    private List<Chunk> chunkFile(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return List.of(); // unreadable or not UTF-8 text: skip it
        }
        if (lines.stream().anyMatch(l -> l.indexOf('\0') >= 0)) {
            return List.of(); // binary file
        }
        if (rel.endsWith(".java")) {
            List<Chunk> byMethod = chunkJava(rel, lines);
            if (!byMethod.isEmpty()) {
                return byMethod;
            }
        }
        return slice(rel, lines, 1, lines.size(), "block", "", BLOCK_LINES);
    }

    private List<Chunk> chunkJava(String rel, List<String> lines) {
        List<Chunk> out = new ArrayList<>();
        ParseResult<CompilationUnit> result = javaParser.parse(String.join("\n", lines));
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return out; // parse failed: caller falls back to plain blocks
        }
        CompilationUnit cu = result.getResult().get();
        cu.findAll(MethodDeclaration.class)
                .forEach(m -> addNode(out, rel, lines, m, "method", m.getNameAsString()));
        cu.findAll(ConstructorDeclaration.class)
                .forEach(c -> addNode(out, rel, lines, c, "constructor", c.getNameAsString()));
        out.sort(Comparator.comparingInt(Chunk::startLine));
        return out;
    }

    private void addNode(List<Chunk> out, String rel, List<String> lines,
                         Node node, String kind, String name) {
        node.getRange().ifPresent(r ->
                out.addAll(slice(rel, lines, r.begin.line, r.end.line, kind, name, METHOD_MAX_LINES)));
    }

    // Cuts lines [from..to] (1-based) into pieces of at most maxLines, overlapping a little.
    private List<Chunk> slice(String rel, List<String> lines, int from, int to,
                              String kind, String name, int maxLines) {
        List<Chunk> out = new ArrayList<>();
        int step = maxLines - OVERLAP;
        for (int start = from; start <= to; start += step) {
            int end = Math.min(start + maxLines - 1, to);
            String text = String.join("\n", lines.subList(start - 1, end));
            if (!text.isBlank()) {
                out.add(new Chunk(rel, start, end, kind, name, text));
            }
            if (end == to) {
                break;
            }
        }
        return out;
    }
}