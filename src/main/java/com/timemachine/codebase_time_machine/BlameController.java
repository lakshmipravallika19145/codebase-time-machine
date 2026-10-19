package com.timemachine.codebase_time_machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class BlameController {

    private final BlameService blameService;
    private final FullAnswerService fullAnswerService;
    private final JdbcTemplate jdbc;

    public BlameController(BlameService blameService, FullAnswerService fullAnswerService, JdbcTemplate jdbc) {
        this.blameService = blameService;
        this.fullAnswerService = fullAnswerService;
        this.jdbc = jdbc;
    }

    @PostMapping("/index-blame")
    public Map<String, Object> indexBlame(@RequestBody Map<String, String> body) throws Exception {
        String repo = body.get("repo");
        requireValidName(repo);
        Integer chunks = jdbc.queryForObject(
                "SELECT count(*) FROM code_chunks WHERE repo = ?", Integer.class, repo);
        if (chunks == null || chunks == 0 || !Files.isDirectory(Path.of("repos", repo, ".git"))) {
            throw bad("Index the repo first with /index");
        }
        return blameService.index(repo);
    }

    // Shows which commits last changed the lines of the chunk(s) around a given line.
    @GetMapping("/blame")
    public List<Map<String, Object>> blame(@RequestParam("repo") String repo,
                                           @RequestParam("file") String file,
                                           @RequestParam("line") int line) {
        requireValidName(repo);
        return jdbc.queryForList(
                "SELECT c.start_line, c.end_line, left(b.hash, 7) AS hash, b.author, b.committed_at, "
                        + "b.lines, left(b.message, 120) AS message "
                        + "FROM code_chunks c JOIN chunk_blame b ON b.chunk_id = c.id "
                        + "WHERE c.repo = ? AND c.file_path = ? AND c.start_line <= ? AND c.end_line >= ? "
                        + "ORDER BY c.start_line, b.lines DESC",
                repo, file, line, line);
    }

    @PostMapping("/ask-full")
    public Map<String, Object> askFull(@RequestBody Map<String, String> body) {
        String repo = body.get("repo");
        requireValidName(repo);
        String question = body.get("question");
        if (question == null || question.isBlank() || question.length() > 500) {
            throw bad("Need a question of up to 500 characters");
        }
        return fullAnswerService.answer(repo, question.trim());
    }

    private static void requireValidName(String repo) {
        if (!RepoCloner.isValidName(repo)) {
            throw bad("Bad repo name");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}