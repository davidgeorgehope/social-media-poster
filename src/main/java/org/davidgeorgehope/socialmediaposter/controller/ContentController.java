package org.davidgeorgehope.socialmediaposter.controller;

import org.davidgeorgehope.socialmediaposter.service.ElasticsearchService;
import org.davidgeorgehope.socialmediaposter.service.LinkedInService;
import org.davidgeorgehope.socialmediaposter.service.ElasticsearchOpenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;

@Controller
@RequestMapping("/content")
public class ContentController {
    private static final Logger logger = LoggerFactory.getLogger(ContentController.class);

    private final ElasticsearchService elasticsearchService;
    private final LinkedInService linkedInService;
    private final ElasticsearchOpenAIService elasticsearchOpenAIService;

    @Autowired
    public ContentController(ElasticsearchService elasticsearchService, LinkedInService linkedInService, ElasticsearchOpenAIService elasticsearchOpenAIService) {
        this.elasticsearchService = elasticsearchService;
        this.linkedInService = linkedInService;
        this.elasticsearchOpenAIService = elasticsearchOpenAIService;
    }

    @GetMapping
    public String listContent(Model model) throws IOException {
        logger.error(  elasticsearchService.getContentFromIndex().toString());

        model.addAttribute("contentList", elasticsearchService.getContentFromIndex());
        return "content-list";
    }

    @GetMapping("/edit")
    public String editContent(@RequestParam String id, Model model) throws IOException {
        // Fetch specific content by id and add to model
        // For simplicity, we're reusing the list method. In a real app, you'd fetch a specific item.
        logger.error(elasticsearchService.getContentFromIndex().toString());
        model.addAttribute("contents", elasticsearchService.getContentFromIndex());
        model.addAttribute("editId", id);
        return "content-edit";
    }

    @PostMapping("/update")
    public String updateContent(@RequestParam String id, 
                                @RequestParam Map<String, Object> content,
                                @RequestParam(required = false) MultipartFile mediaFile,
                                @RequestParam(defaultValue = "true") boolean useAI) throws IOException {
        String text = (String) content.get("text");
        if (useAI) {
            text = elasticsearchOpenAIService.processContent(text);
        }
        content.put("text", text);
        
        if (mediaFile != null && !mediaFile.isEmpty()) {
            String mediaUrl = elasticsearchService.uploadMedia(mediaFile);
            content.put("mediaUrl", mediaUrl);
            content.put("mediaType", mediaFile.getContentType().startsWith("image/") ? "image" : "video");
        } else {
            // Remove existing media if no new media is uploaded
            content.remove("mediaUrl");
            content.remove("mediaType");
        }
        
        elasticsearchService.updateContent(id, content);
        return "redirect:/content";
    }

    @PostMapping("/post/{id}")
    public String postToLinkedIn(@PathVariable String id, @RequestParam String email) throws IOException {
        // Fetch content by id
        Map<String, Object> content = elasticsearchService.getContentFromIndex().stream()
                .filter(c -> id.equals(c.get("_id")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Content not found"));

        // Check if 'text' field exists and is a Map
        Object textObject = content.get("text");
        String message;
        if (textObject instanceof Map) {
            message = (String) ((Map<?, ?>) textObject).get("text");
        } else if (textObject instanceof String) {
            message = (String) textObject;
        } else {
            throw new RuntimeException("Invalid content format");
        }

        if (message == null || message.isEmpty()) {
            throw new RuntimeException("Content text is empty");
        }

        String mediaUrl = (String) content.get("mediaUrl");
        String mediaType = (String) content.get("mediaType");

        linkedInService.postToLinkedIn(message, email, mediaUrl, mediaType);

        // Update last_posted_date
        content.put("last_posted_date", Instant.now().toString());
        elasticsearchService.updateContent(id, content);

        return "redirect:/content";
    }

    @GetMapping("/create")
    public String showCreateForm() {
        return "content-create";
    }

    @PostMapping("/create")
    public String createContent(@RequestParam String text, 
                                @RequestParam(required = false) MultipartFile mediaFile,
                                @RequestParam(defaultValue = "true") boolean useAI) throws IOException {
        if (useAI) {
            text = elasticsearchOpenAIService.processContent(text);
        }
        
        Map<String, Object> content = new HashMap<>();
        content.put("text", text);
        
        if (mediaFile != null && !mediaFile.isEmpty()) {
            String mediaUrl = elasticsearchService.uploadMedia(mediaFile);
            content.put("mediaUrl", mediaUrl);
            content.put("mediaType", mediaFile.getContentType().startsWith("image/") ? "image" : "video");
        }
        
        elasticsearchService.indexContent(content);
        return "redirect:/content";
    }

    @PostMapping("/delete/{id}")
    @ResponseBody
    public ResponseEntity<String> deleteContent(@PathVariable String id) {
        try {
            elasticsearchService.deleteContent(id);
            return ResponseEntity.ok("Content deleted successfully");
        } catch (IOException e) {
            logger.error("Error deleting content with id: " + id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Error deleting content: " + e.getMessage());
        }
    }
}