package com.timemachine.codebase_time_machine;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AnswerController {

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping("/ask-code")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        String repo = body.get("repo");
        String question = body.get("question");
        if (!RepoCloner.isValidName(repo) || question == null || question.isBlank()
                || question.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Need a valid repo and a question of up to 500 characters");
        }
        return answerService.answer(repo, question.trim());
    }
}