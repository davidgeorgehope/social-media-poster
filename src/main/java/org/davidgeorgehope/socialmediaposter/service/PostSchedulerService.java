package org.davidgeorgehope.socialmediaposter.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.time.format.DateTimeParseException;

@Service
public class PostSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(PostSchedulerService.class);

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

    //@Scheduled(cron = "0 25 14 * * ?") // Run daily at 1:20 PM
    @Scheduled(fixedRate = 86400000, initialDelay = 10000) // Runs every 24 hours after an initial delay of 10 seconds
    public void schedulePost() throws IOException {
        logger.info("Starting schedulePost() method at {}", LocalDateTime.now());
        
        List<Map<String, Object>> eligibleContent = getEligibleContent();
        logger.info("Found {} eligible content items", eligibleContent.size());

        if (!eligibleContent.isEmpty()) {
            Map<String, Object> selectedContent = selectContent(eligibleContent);
            logger.info("Selected content with ID: {}", selectedContent.get("_id"));
            postContent(selectedContent);
        } else {
            logger.info("No eligible content found. Generating new content.");
            Map<String, Object> generatedContent = generateNewContent();
            postContent(generatedContent);
        }
        
        logger.info("Finished schedulePost() method at {}", LocalDateTime.now());
    }

    private List<Map<String, Object>> getEligibleContent() throws IOException {
        logger.info("Starting getEligibleContent() method");
        List<Map<String, Object>> availableContent = elasticsearchService.getContentForScheduling();
        logger.info("Retrieved {} available content items from Elasticsearch", availableContent.size());
        
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        logger.info("Filtering content posted before {}", thirtyDaysAgo);

        List<Map<String, Object>> eligibleContent = availableContent.stream()
            .filter(content -> {
                String lastPostedDateStr = (String) content.get("last_posted_date");
                if (lastPostedDateStr == null || lastPostedDateStr.isEmpty()) {
                    logger.info("Content with ID {} has never been posted", content.get("_id"));
                    return true; // Content that has never been posted is eligible
                }
                try {
                    Instant lastPostedInstant = Instant.parse(lastPostedDateStr);
                    LocalDateTime lastPostedDate = LocalDateTime.ofInstant(lastPostedInstant, ZoneId.systemDefault());
                    boolean isEligible = lastPostedDate.isBefore(thirtyDaysAgo);
                    logger.info("Content with ID {} last posted on {}. Eligible: {}", content.get("_id"), lastPostedDate, isEligible);
                    return isEligible;
                } catch (DateTimeParseException e) {
                    logger.error("Error parsing date: {} for content ID: {}. Considering content eligible.", lastPostedDateStr, content.get("_id"), e);
                    return true;
                }
            })
            .collect(Collectors.toList());

        logger.info("Finished getEligibleContent() method. Found {} eligible items", eligibleContent.size());
        return eligibleContent;
    }

    private Map<String, Object> selectContent(List<Map<String, Object>> eligibleContent) {
        // Select a random piece of content from the eligible content
        return eligibleContent.get(new Random().nextInt(eligibleContent.size()));
    }

    private void postContent(Map<String, Object> content) throws IOException {
        logger.info("Starting postContent method with content ID: {}", content.get("_id"));

        String contentId = content.get("_id").toString();
        Object textObj = content.get("text");
        String text = (textObj instanceof String) ? (String) textObj : 
                      (textObj instanceof Map) ? ((Map<?, ?>) textObj).get("text").toString() : 
                      "No text available";
        
        Object mediaUrlObj = content.get("mediaUrl");
        String mediaUrl = (mediaUrlObj instanceof String) ? (String) mediaUrlObj : "";
        
        Object mediaTypeObj = content.get("mediaType");
        String mediaType = (mediaTypeObj instanceof String) ? (String) mediaTypeObj : "";

        logger.info("Posting content - ID: {}, Text: {}, MediaUrl: {}, MediaType: {}", 
                    contentId, text, mediaUrl, mediaType);

        linkedInService.postToLinkedIn(text, userEmail, mediaUrl, mediaType);

        // Update the last_posted_date in Elasticsearch
        content.put("last_posted_date", Instant.now().toString());
        elasticsearchService.updateContent(contentId, content);

        logger.info("Successfully posted and updated content with ID: {}", contentId);
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