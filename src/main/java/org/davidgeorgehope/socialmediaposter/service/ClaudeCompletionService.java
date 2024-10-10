package org.davidgeorgehope.socialmediaposter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeCompletionService implements AICompletionService {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiUrl = "https://api.anthropic.com/v1/messages";

    public ClaudeCompletionService(RestTemplate restTemplate, @Value("${claude.api.key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
    }

    @Override
    public String generateCompletion(String systemPrompt, String userPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "claude-3-5-sonnet-20240620");
        requestBody.put("max_tokens", 1000);
        requestBody.put("system", systemPrompt);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", userPrompt)
        ));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        Map<String, Object> response = restTemplate.postForObject(apiUrl, request, Map.class);

        if (response != null && response.containsKey("content")) {
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            if (!content.isEmpty() && content.get(0).containsKey("text")) {
                return (String) content.get(0).get("text");
            }
        }

        throw new RuntimeException("Failed to get response from Claude API");
    }
}