package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.util.FileUtils;
import org.springframework.stereotype.Service;

@Service
public class RepoCloner {

    private static final Path BASE = Path.of("repos");

    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    }

    public String repoName(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String last = u.substring(u.lastIndexOf('/') + 1);
        return last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
    }

    public Path clone(String url) throws Exception {
        String name = repoName(url);
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Bad repo name: " + name);
        }
        Path dir = BASE.resolve(name);

        if (hasFiles(dir)) {
            return dir; // a real download is already there, reuse it
        }
        if (Files.exists(dir)) {
            // half-finished download: remove it and start fresh
            FileUtils.delete(dir.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        }

        Files.createDirectories(BASE);
        try (Git git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .call()) {
            if (git.getRepository().resolve("HEAD") == null) {
                throw new IllegalStateException("Clone finished but the repository is empty: " + url);
            }
        }
        return dir;
    }

    // true if the folder contains anything besides the hidden .git folder
    private boolean hasFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.anyMatch(p -> !p.getFileName().toString().equals(".git"));
        }
    }
}