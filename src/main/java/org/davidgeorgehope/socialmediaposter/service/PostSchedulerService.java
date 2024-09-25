package org.davidgeorgehope.socialmediaposter.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

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
        List<Map<String, Object>> eligibleContent = getEligibleContent();

        if (!eligibleContent.isEmpty()) {
            Map<String, Object> selectedContent = selectContent(eligibleContent);
            postContent(selectedContent);
        } else {
            Map<String, Object> generatedContent = generateNewContent();
            postContent(generatedContent);
        }
    }

    private List<Map<String, Object>> getEligibleContent() throws IOException {
        List<Map<String, Object>> availableContent = elasticsearchService.getContentForScheduling();
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        return availableContent.stream()
            .filter(content -> {
                String lastPostedDateStr = (String) content.get("last_posted_date");
                if (lastPostedDateStr == null || lastPostedDateStr.isEmpty()) {
                    return true; // Content that has never been posted is eligible
                }
                LocalDateTime lastPostedDate = LocalDateTime.parse(lastPostedDateStr, formatter);
                return lastPostedDate.isBefore(thirtyDaysAgo);
            })
            .collect(Collectors.toList());
    }

    private Map<String, Object> selectContent(List<Map<String, Object>> eligibleContent) {
        // Select a random piece of content from the eligible content
        return eligibleContent.get(new Random().nextInt(eligibleContent.size()));
    }

    private void postContent(Map<String, Object> content) throws IOException {
        String contentId = (String) content.get("_id");
        String text = (String) content.get("text");
        String mediaUrl = (String) content.get("mediaUrl");
        String mediaType = (String) content.get("mediaType");

        linkedInService.postToLinkedIn(text, userEmail, mediaUrl, mediaType);

        // Update the last_posted_date in Elasticsearch
        content.put("last_posted_date", Instant.now().toString());
        elasticsearchService.updateContent(contentId, content);
    }

    private Map<String, Object> generateNewContent() throws IOException {
        String prompt = "Generate a LinkedIn post about Elastic Observability for Site Reliability Engineers. Focus on how it helps prevent downtime, consolidates tool stacks, and reduces toil.";
        String generatedText = elasticsearchOpenAIService.processQuestion(prompt);
        
        Map<String, Object> newContent = Map.of(
            "text", generatedText,
            "last_posted_date", Instant.now().toString(),
            "mediaUrl", "",
            "mediaType", ""
        );
        
        // Index the new content
        String contentId = elasticsearchService.indexContent(newContent);
        newContent.put("_id", contentId);
        
        return newContent;
    }
}