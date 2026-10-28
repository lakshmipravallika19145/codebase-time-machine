package com.timemachine.codebase_time_machine;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProjectController {

    private final ProjectMapService projectMap;
    private final SummaryService summaries;

    public ProjectController(ProjectMapService projectMap, SummaryService summaries) {
        this.projectMap = projectMap;
        this.summaries = summaries;
    }

    @GetMapping(value = "/tree", produces = "text/plain;charset=UTF-8")
    public String tree(@RequestParam("repo") String repo) throws IOException {
        requireValidName(repo);
        return projectMap.render(repo);
    }

    @PostMapping("/index-summaries")
    public Map<String, Object> indexSummaries(@RequestBody Map<String, String> body) throws Exception {
        String repo = body.get("repo");
        requireValidName(repo);
        int limit = 10;
        String raw = body.get("limit");
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be a number");
            }
        }
        return summaries.generate(repo, Math.max(1, Math.min(limit, 20)));
    }

    private static void requireValidName(String repo) {
        if (!RepoCloner.isValidName(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad repo name");
        }
    }
}