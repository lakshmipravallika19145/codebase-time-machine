package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BlameService {

    private static final int MAX_COMMITS_PER_CHUNK = 5;
    private static final int MAX_MESSAGE_CHARS = 2000;

    private record Details(String hash, String author, Instant committedAt, String message) {}

    private record Row(long chunkId, Details commit, int lines) {}

    private final JdbcTemplate jdbc;

    public BlameService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> index(String repo) throws Exception {
        List<Map<String, Object>> chunks = jdbc.queryForList(
                "SELECT id, file_path, start_line, end_line FROM code_chunks "
                        + "WHERE repo = ? ORDER BY file_path, start_line", repo);

        // one blame per file, shared by all the chunks of that file
        Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
        for (Map<String, Object> c : chunks) {
            byFile.computeIfAbsent((String) c.get("file_path"), k -> new ArrayList<>()).add(c);
        }

        List<Row> rows = new ArrayList<>();
        Map<String, Details> cache = new HashMap<>();
        int skipped = 0;

        try (Git git = Git.open(Path.of("repos", repo).toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : byFile.entrySet()) {
                BlameResult blame = blameFile(git, entry.getKey());
                if (blame == null) {
                    skipped++;
                    continue; // file not tracked by git, or blame failed
                }
                int total = blame.getResultContents().size();

                for (Map<String, Object> c : entry.getValue()) {
                    long chunkId = ((Number) c.get("id")).longValue();
                    int start = (Integer) c.get("start_line");
                    int end = (Integer) c.get("end_line");

                    // count how many lines of this chunk each commit last changed
                    Map<String, Integer> counts = new HashMap<>();
                    for (int line = start; line <= end && line <= total; line++) {
                        RevCommit src = blame.getSourceCommit(line - 1);
                        if (src != null) {
                            counts.merge(src.getName(), 1, Integer::sum);
                        }
                    }

                    List<Row> perChunk = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        Details d = cache.get(e.getKey());
                        if (d == null) {
                            d = load(walk, e.getKey());
                            cache.put(e.getKey(), d);
                        }
                        perChunk.add(new Row(chunkId, d, e.getValue()));
                    }
                    // biggest contributors first, newest first on ties
                    perChunk.sort((a, b) -> {
                        int byLines = Integer.compare(b.lines(), a.lines());
                        return byLines != 0 ? byLines
                                : b.commit().committedAt().compareTo(a.commit().committedAt());
                    });
                    rows.addAll(perChunk.subList(0, Math.min(MAX_COMMITS_PER_CHUNK, perChunk.size())));
                }
            }
        }

        jdbc.update("DELETE FROM chunk_blame WHERE repo = ?", repo);
        jdbc.batchUpdate(
                "INSERT INTO chunk_blame (chunk_id, repo, hash, author, committed_at, message, lines) "
                        + "VALUES (?,?,?,?,?,?,?)",
                rows, 500, (ps, r) -> {
                    ps.setLong(1, r.chunkId());
                    ps.setString(2, repo);
                    ps.setString(3, r.commit().hash());
                    ps.setString(4, r.commit().author());
                    ps.setTimestamp(5, Timestamp.from(r.commit().committedAt()));
                    ps.setString(6, r.commit().message());
                    ps.setInt(7, r.lines());
                });

        return Map.of("repo", repo, "chunks", chunks.size(), "filesBlamed", byFile.size() - skipped,
                "skippedFiles", skipped, "blameRows", rows.size());
    }

    private BlameResult blameFile(Git git, String path) {
        try {
            BlameResult b = git.blame()
                    .setFilePath(path)
                    .setTextComparator(RawTextComparator.WS_IGNORE_ALL) // ignore whitespace-only changes
                    .call();
            if (b != null) {
                b.computeAll();
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private Details load(RevWalk walk, String hash) throws IOException {
        RevCommit c = walk.parseCommit(ObjectId.fromString(hash));
        String message = c.getFullMessage().strip().replace("\0", "");
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS);
        }
        return new Details(hash, c.getAuthorIdent().getName().replace("\0", ""),
                Instant.ofEpochSecond(c.getCommitTime()), message);
    }
}