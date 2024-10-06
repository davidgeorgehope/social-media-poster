package org.davidgeorgehope.socialmediaposter.controller;

import org.davidgeorgehope.socialmediaposter.service.ElasticsearchOpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class ChatController {

    @Autowired
    private ElasticsearchOpenAIService elasticsearchOpenAIService;

    @PostMapping("/api/chat")
    public String chat(@RequestBody Map<String, String> request) throws IOException {
        String message = request.get("message");
        return elasticsearchOpenAIService.processAssistantQuestion(message);
    }
}