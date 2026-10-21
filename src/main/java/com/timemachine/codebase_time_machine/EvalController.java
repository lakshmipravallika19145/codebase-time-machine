package com.timemachine.codebase_time_machine;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class EvalController {

    private final EvalService eval;
    private final boolean enabled;

    public EvalController(EvalService eval, @Value("${app.eval.enabled:true}") boolean enabled) {
        this.eval = eval;
        this.enabled = enabled;
    }

    @PostMapping("/eval/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) throws Exception {
        guard();
        String repo = body.get("repo");
        requireValidName(repo);
        int count = 8;
        String raw = body.get("count");
        if (raw != null && !raw.isBlank()) {
            try {
                count = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw bad("count must be a number");
            }
        }
        return eval.generate(repo, Math.max(1, Math.min(count, 10)));
    }

    @GetMapping("/eval/cases")
    public List<Map<String, Object>> cases(@RequestParam("repo") String repo) {
        guard();
        requireValidName(repo);
        return eval.list(repo);
    }

    @PostMapping("/eval/cases")
    public Map<String, Object> addCase(@RequestBody Map<String, String> body) {
        guard();
        String repo = body.get("repo");
        requireValidName(repo);
        String question = body.get("question");
        String file = body.get("file");
        String name = body.get("name");
        if (question == null || question.isBlank() || question.length() > 300
                || file == null || file.isBlank() || name == null || name.isBlank()) {
            throw bad("Need question (up to 300 chars), file and name");
        }
        return eval.addManual(repo, question.trim(), file.trim(), name.trim());
    }

    @PostMapping("/eval/cases/delete")
    public Map<String, Object> deleteCase(@RequestBody Map<String, String> body) {
        guard();
        try {
            return Map.of("deleted", eval.delete(Long.parseLong(body.get("id").trim())));
        } catch (NumberFormatException | NullPointerException e) {
            throw bad("Need a numeric id");
        }
    }

    @GetMapping("/eval/run")
    public Map<String, Object> run(@RequestParam("repo") String repo,
                                   @RequestParam(value = "source", required = false) String source) {
        guard();
        requireValidName(repo);
        return eval.run(repo, source);
    }

    @GetMapping("/eval/answers")
    public Map<String, Object> answers(@RequestParam("repo") String repo,
                                       @RequestParam(value = "limit", defaultValue = "8") int limit)
            throws Exception {
        guard();
        requireValidName(repo);
        return eval.runAnswers(repo, Math.min(limit, 15));
    }

    // These endpoints use AI tokens and change data, so a public deployment turns them off.
    private void guard() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
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