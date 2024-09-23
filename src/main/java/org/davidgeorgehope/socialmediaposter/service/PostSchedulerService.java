package org.davidgeorgehope.socialmediaposter.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class PostSchedulerService {

    private final ElasticsearchService elasticsearchService;
    private final LinkedInService linkedInService;
    private final String userEmail;
    @Autowired
    private ElasticsearchOpenAIService elasticsearchOpenAIService;

    @Autowired
    public PostSchedulerService(ElasticsearchService elasticsearchService, 
                                LinkedInService linkedInService,
                                @Value("${linkedin.user-email}") String userEmail) {
        this.elasticsearchService = elasticsearchService;
        this.linkedInService = linkedInService;
        this.userEmail = userEmail;
    }

    @Scheduled(cron = "0 0 12 * * ?") // Run daily at noon
    public void schedulePost() throws IOException {
        List<Map<String, Object>> availableContent = elasticsearchService.getContentForScheduling();

        if (!availableContent.isEmpty()) {
            Map<String, Object> selectedContent = selectContent(availableContent);
            postContent(selectedContent);
        } else {
            Map<String, Object> generatedContent = generateNewContent();
            postContent(generatedContent);
        }
    }

    private Map<String, Object> selectContent(List<Map<String, Object>> availableContent) {
        // For simplicity, we're just selecting a random piece of content
        return availableContent.get(new Random().nextInt(availableContent.size()));
    }

    private void postContent(Map<String, Object> content) throws IOException {
        String contentId = (String) content.get("_id");
        String text = (String) content.get("text");

        linkedInService.postToLinkedIn(text, userEmail);

        // Update the last_posted_date in Elasticsearch
        content.put("last_posted_date", Instant.now().toString());
        elasticsearchService.updateContent(contentId, content);
    }

    private Map<String, Object> generateNewContent() throws IOException {
        String prompt = "Generate a LinkedIn post about Elastic Observability for Site Reliability Engineers. Focus on how it helps prevent downtime, consolidates tool stacks, and reduces toil.";
        String generatedText = elasticsearchOpenAIService.processQuestion(prompt);
        
        Map<String, Object> newContent = Map.of("text", generatedText);
        
        // Index the new content
        elasticsearchService.indexContent(generatedText);
        
        return newContent;
    }
}