package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

@Service
public class HistoryReader {

    public record CommitInfo(String hash, String author, Instant committedAt,
                             String message, String files, int additions, int deletions) {}

    private static final int MAX_FILES_LISTED = 25;
    private static final int MAX_FILES_DIFFED = 200;
    private static final int MAX_MESSAGE_CHARS = 2000;

    public List<CommitInfo> read(Path repoDir, int maxCommits) throws Exception {
        List<CommitInfo> out = new ArrayList<>();
        try (Git git = Git.open(repoDir.toFile());
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(git.getRepository());
            df.setDetectRenames(true);
            for (RevCommit c : git.log().call()) {
                if (out.size() >= maxCommits) {
                    break;
                }
                if (c.getParentCount() > 1) {
                    continue; // skip merge commits: they only repeat other commits
                }
                out.add(describe(df, c));
            }
        }
        return out;
    }

    private CommitInfo describe(DiffFormatter df, RevCommit c) throws IOException {
        // compare this commit with its parent (or with "nothing" for the very first commit)
        List<DiffEntry> diffs = df.scan(
                c.getParentCount() == 0 ? null : c.getParent(0).getId(), c.getId());
        boolean bulk = diffs.size() > MAX_FILES_DIFFED;
        int add = 0;
        int del = 0;
        int listed = 0;
        StringBuilder files = new StringBuilder();

        for (DiffEntry e : diffs) {
            int a = 0;
            int d = 0;
            if (!bulk) {
                try {
                    FileHeader fh = df.toFileHeader(e);
                    for (Edit ed : fh.toEditList()) {
                        a += ed.getEndB() - ed.getBeginB();
                        d += ed.getEndA() - ed.getBeginA();
                    }
                } catch (Exception ignored) {
                    // binary or huge file: skip the line counts
                }
            }
            add += a;
            del += d;
            if (listed < MAX_FILES_LISTED) {
                String path = e.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? e.getOldPath() : e.getNewPath();
                files.append(label(e.getChangeType())).append(' ').append(path);
                if (!bulk) {
                    files.append(" (+").append(a).append(" -").append(d).append(')');
                }
                files.append('\n');
                listed++;
            }
        }
        if (diffs.size() > listed) {
            files.append("... and ").append(diffs.size() - listed).append(" more files\n");
        }

        String message = clean(c.getFullMessage().strip());
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS);
        }
        return new CommitInfo(c.getName(), clean(c.getAuthorIdent().getName()),
                Instant.ofEpochSecond(c.getCommitTime()), message,
                clean(files.toString().strip()), add, del);
    }

    private static String clean(String s) {
        return s.replace("\0", ""); // Postgres can't store NUL characters
    }

    private static String label(DiffEntry.ChangeType t) {
        return switch (t) {
            case ADD -> "A";
            case DELETE -> "D";
            case RENAME -> "R";
            case COPY -> "C";
            default -> "M";
        };
    }
}