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

    @GetMapping("/edit/{id}")
    public String editContent(@PathVariable String id, Model model) throws IOException {
        // Fetch specific content by id and add to model
        // For simplicity, we're reusing the list method. In a real app, you'd fetch a specific item.
        logger.error(  elasticsearchService.getContentFromIndex().toString());
        model.addAttribute("contents", elasticsearchService.getContentFromIndex());
        model.addAttribute("editId", id);
        return "content-edit";
    }

    @PostMapping("/update/{id}")
    public String updateContent(@PathVariable String id, @RequestParam Map<String, Object> content) throws IOException {
        elasticsearchService.updateContent(id, content);
        return "redirect:/content";
    }

    @PostMapping("/post/{id}")
    public String postToLinkedIn(@PathVariable String id, @RequestParam String email) throws IOException {
        // Fetch content by id
        Map<String, Object> content = elasticsearchService.getContentFromIndex().stream()
                .filter(c -> c.get("id").equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Content not found"));

        String message = (String) content.get("text");
        linkedInService.postToLinkedIn(message, email);
        return "redirect:/content";
    }
}