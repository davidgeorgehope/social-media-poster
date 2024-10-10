package org.davidgeorgehope.socialmediaposter.service;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OpenAICompletionService implements AICompletionService {

    private final OpenAiService openAiService;

    public OpenAICompletionService(OpenAiService openAiService) {
        this.openAiService = openAiService;
    }

    @Override
    public String generateCompletion(String systemPrompt, String userPrompt) {
        ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
            .model("gpt-4")
            .messages(List.of(
                new ChatMessage("system", systemPrompt),
                new ChatMessage("user", userPrompt)
            ))
            .build();

        return openAiService.createChatCompletion(completionRequest).getChoices().get(0).getMessage().getContent();
    }
}