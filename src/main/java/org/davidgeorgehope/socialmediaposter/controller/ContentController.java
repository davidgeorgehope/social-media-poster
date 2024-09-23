package org.davidgeorgehope.socialmediaposter.controller;

import org.davidgeorgehope.socialmediaposter.service.ElasticsearchService;
import org.davidgeorgehope.socialmediaposter.service.LinkedInService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

@Controller
@RequestMapping("/content")
public class ContentController {
    private static final Logger logger = LoggerFactory.getLogger(ContentController.class);

    private final ElasticsearchService elasticsearchService;
    private final LinkedInService linkedInService;

    @Autowired
    public ContentController(ElasticsearchService elasticsearchService, LinkedInService linkedInService) {
        this.elasticsearchService = elasticsearchService;
        this.linkedInService = linkedInService;
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
    public String updateContent(@RequestParam String id, @RequestParam Map<String, Object> content) throws IOException {
        elasticsearchService.updateContent(id, content);
        return "redirect:/content";
    }

    @PostMapping("/post/{id}")
    public String postToLinkedIn(@PathVariable String id, @RequestParam String email) throws IOException {
        // Fetch content by id
        Map<String, Object> content = elasticsearchService.getContentFromIndex().stream()
                .filter(c -> id.equals(c.get("_id"))) // Change this line
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

        linkedInService.postToLinkedIn(message, email);
        return "redirect:/content";
    }

    @GetMapping("/create")
    public String showCreateForm() {
        return "content-create";
    }

    @PostMapping("/create")
    public String createContent(@RequestParam String text) throws IOException {

        elasticsearchService.indexContent(text);
        return "redirect:/content";
    }
}