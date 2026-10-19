package com.timemachine.codebase_time_machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class HistoryController {

    private final HistoryService historyService;
    private final HistorySearchService searchService;
    private final HistoryAnswerService answerService;

    public HistoryController(HistoryService historyService, HistorySearchService searchService,
                             HistoryAnswerService answerService) {
        this.historyService = historyService;
        this.searchService = searchService;
        this.answerService = answerService;
    }

    @PostMapping("/index-history")
    public Map<String, Object> indexHistory(@RequestBody Map<String, String> body) throws Exception {
        String repo = body.get("repo");
        requireValidName(repo);
        if (!Files.isDirectory(Path.of("repos", repo, ".git"))) {
            throw bad("Index the repo first with /index");
        }
        int max = 500;
        String raw = body.get("maxCommits");
        if (raw != null && !raw.isBlank()) {
            try {
                max = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw bad("maxCommits must be a number");
            }
        }
        return historyService.index(repo, Math.max(1, Math.min(max, 2000)));
    }

    @GetMapping("/commits")
    public List<CommitHit> commits(@RequestParam("repo") String repo,
                                   @RequestParam(value = "author", required = false) String author,
                                   @RequestParam(value = "since", required = false) String since,
                                   @RequestParam(value = "until", required = false) String until,
                                   @RequestParam(value = "file", required = false) String file,
                                   @RequestParam(value = "limit", defaultValue = "10") int limit) {
        requireValidName(repo);
        return searchService.recent(repo,
                new HistorySearchService.Filters(author, date(since), date(until), file),
                Math.min(limit, 50));
    }

    @PostMapping("/ask-history")
    public Map<String, Object> askHistory(@RequestBody Map<String, String> body) {
        String repo = body.get("repo");
        requireValidName(repo);
        String question = body.get("question");
        if (question == null || question.isBlank() || question.length() > 500) {
            throw bad("Need a question of up to 500 characters");
        }
        HistorySearchService.Filters f = new HistorySearchService.Filters(
                body.get("author"), date(body.get("since")), date(body.get("until")), body.get("file"));
        boolean recent = "recent".equalsIgnoreCase(body.get("mode"));
        return answerService.answer(repo, question.trim(), f, recent);
    }

    private static void requireValidName(String repo) {
        if (!RepoCloner.isValidName(repo)) {
            throw bad("Bad repo name");
        }
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw bad("Dates must look like 2026-09-30");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}