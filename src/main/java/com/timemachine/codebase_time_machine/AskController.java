package com.timemachine.codebase_time_machine;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final ChatClient chatClient;

    public AskController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) {
        String answer = chatClient.prompt()
                .user(body.get("question"))
                .call()
                .content();
        return Map.of("answer", answer);
    }
}