package com.timemachine.codebase_time_machine;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("repo") String repo,
                                            @RequestParam("q") String q,
                                            @RequestParam(value = "k", defaultValue = "5") int k) {
        if (!RepoCloner.isValidName(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad repo name");
        }
        return searchService.search(repo, q, Math.min(k, 20));
    }
}