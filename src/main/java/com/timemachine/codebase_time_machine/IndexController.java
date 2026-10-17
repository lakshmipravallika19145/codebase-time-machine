package com.timemachine.codebase_time_machine;

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
public class IndexController {

    private final IndexService indexService;
    private final JdbcTemplate jdbc;

    public IndexController(IndexService indexService, JdbcTemplate jdbc) {
        this.indexService = indexService;
        this.jdbc = jdbc;
    }

    @PostMapping("/index")
    public Map<String, Object> index(@RequestBody Map<String, String> body) throws Exception {
        String url = body.get("repoUrl");
        if (url == null || !url.startsWith("https://github.com/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a https://github.com/... URL");
        }
        return indexService.index(url);
    }

    @GetMapping("/chunks")
    public List<Map<String, Object>> chunks(@RequestParam("repo") String repo,
                                            @RequestParam(value = "contains", required = false) String contains,
                                            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        String like = "%" + (contains == null ? "" : contains) + "%";
        return jdbc.queryForList(
                "SELECT file_path, start_line, end_line, kind, name FROM code_chunks "
                        + "WHERE repo = ? AND content ILIKE ? ORDER BY id LIMIT ?",
                repo, like, Math.min(limit, 50));
    }

    @GetMapping("/verify")
    public Map<String, Object> verify(@RequestParam("repo") String repo) throws Exception {
        if (!RepoCloner.isValidName(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad repo name");
        }
        return indexService.verify(repo);
    }
}