package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ApiController {

    private final JdbcTemplate jdbc;

    public ApiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Which repos are indexed, and how much of each.
    @GetMapping("/repos")
    public List<Map<String, Object>> repos() {
        return jdbc.queryForList(
                "SELECT r.repo, r.chunks, COALESCE(c.commits, 0) AS commits, COALESCE(b.blame, 0) AS blame "
                        + "FROM (SELECT repo, count(*) AS chunks FROM code_chunks GROUP BY repo) r "
                        + "LEFT JOIN (SELECT repo, count(*) AS commits FROM commit_chunks GROUP BY repo) c ON c.repo = r.repo "
                        + "LEFT JOIN (SELECT repo, count(*) AS blame FROM chunk_blame GROUP BY repo) b ON b.repo = r.repo "
                        + "ORDER BY r.repo");
    }

    // The text of one indexed file, for the code viewer.
    @GetMapping(value = "/file", produces = "text/plain;charset=UTF-8")
    public String file(@RequestParam("repo") String repo,
                       @RequestParam("path") String path) throws IOException {
        requireValidName(repo);
        Integer known = jdbc.queryForObject(
                "SELECT count(*) FROM code_chunks WHERE repo = ? AND file_path = ?",
                Integer.class, repo, path);
        if (known == null || known == 0) {
            throw notFound("File not found in this repo's index");
        }
        Path base = Path.of("repos", repo).toAbsolutePath().normalize();
        Path file = base.resolve(path).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw notFound("File not found in this repo's index");
        }
        return Files.readString(file);
    }

    // One commit's full details, for the commit panel.
    @GetMapping("/commit")
    public Map<String, Object> commit(@RequestParam("repo") String repo,
                                      @RequestParam("hash") String hash) {
        requireValidName(repo);
        if (!hash.matches("[0-9a-fA-F]{7,40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad commit hash");
        }
        String prefix = hash.toLowerCase() + "%";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT hash, author, committed_at, message, files, additions, deletions "
                        + "FROM commit_chunks WHERE repo = ? AND hash LIKE ? LIMIT 1", repo, prefix);
        if (rows.isEmpty()) {
            // older commits only appear in the blame data
            rows = jdbc.queryForList(
                    "SELECT hash, author, committed_at, message, NULL AS files, "
                            + "NULL::int AS additions, NULL::int AS deletions "
                            + "FROM chunk_blame WHERE repo = ? AND hash LIKE ? LIMIT 1", repo, prefix);
        }
        if (rows.isEmpty()) {
            throw notFound("Commit not found");
        }
        return rows.get(0);
    }

    private static void requireValidName(String repo) {
        if (!RepoCloner.isValidName(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad repo name");
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}