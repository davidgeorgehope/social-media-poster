package org.davidgeorgehope.socialmediaposter.config;

import org.davidgeorgehope.socialmediaposter.service.AICompletionService;
import org.davidgeorgehope.socialmediaposter.service.ClaudeCompletionService;
import org.davidgeorgehope.socialmediaposter.service.OpenAICompletionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIServiceConfig {

    @Value("${ai.service.provider:openai}")
    private String aiServiceProvider;

    @Bean
    public AICompletionService aiCompletionService(OpenAICompletionService openAIService, ClaudeCompletionService claudeService) {
        return "claude".equalsIgnoreCase(aiServiceProvider) ? claudeService : openAIService;
    }
}