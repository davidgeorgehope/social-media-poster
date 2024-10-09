package org.davidgeorgehope.socialmediaposter.controller;

import org.davidgeorgehope.socialmediaposter.service.ElasticsearchService;
import org.davidgeorgehope.socialmediaposter.service.LinkedInService;
import org.davidgeorgehope.socialmediaposter.service.ElasticsearchOpenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.net.URL;
import java.net.URLConnection;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import org.davidgeorgehope.socialmediaposter.model.ByteArrayMultipartFile;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ExceptionHandler;

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

    @Value("${media.upload.dir}")
    private String mediaUploadDir;

    @GetMapping
    public String listContent(Model model) throws IOException {
        logger.error(  elasticsearchService.getContentFromIndex().toString());

        model.addAttribute("contentList", elasticsearchService.getContentFromIndex());
        return "content-list";
    }

    @GetMapping("/edit")
    public String editContent(@RequestParam String id, Model model) throws IOException {
        List<Map<String, Object>> contents = elasticsearchService.getContentFromIndex();
        Map<String, Object> contentToEdit = contents.stream()
            .filter(content -> id.equals(content.get("_id")))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Content not found"));

        // Prepare media information
        String mediaUrl = (String) contentToEdit.get("mediaUrl");
        if (mediaUrl != null && !mediaUrl.isEmpty()) {
            String filename = mediaUrl.substring(mediaUrl.lastIndexOf('/') + 1);
            contentToEdit.put("mediaFilename", filename);
        }

        model.addAttribute("content", contentToEdit);
        return "content-edit";
    }

    @PostMapping("/update")
    public String updateContent(@RequestParam String id, 
                                @RequestParam Map<String, Object> content,
                                @RequestParam(required = false) MultipartFile mediaFile,
                                @RequestParam(defaultValue = "false") boolean useAI) throws IOException {
        String text = (String) content.get("text");
        String imageUrl = null;

        if (useAI) {

            Map<String, String> fetchedContent = elasticsearchOpenAIService.processContent(text);
            text = fetchedContent.get("content");
            imageUrl = fetchedContent.get("imageUrl");
            
        }
        
        // Create a map for the updated content
        Map<String, Object> updatedContent = new HashMap<>();
        updatedContent.put("text", text);
        
        if (mediaFile != null && !mediaFile.isEmpty()) {
            String mediaUrl = elasticsearchService.uploadMedia(mediaFile);
            updatedContent.put("mediaUrl", mediaUrl);
            updatedContent.put("mediaType", mediaFile.getContentType().startsWith("image/") ? "image" : "video");
        } else if (imageUrl != null) {
            MultipartFile imageFile = downloadImage(imageUrl);
            if (imageFile != null) {
                String mediaUrl = elasticsearchService.uploadMedia(imageFile);
                updatedContent.put("mediaUrl", mediaUrl);
                updatedContent.put("mediaType", "image");
            }
        } else {
            // Preserve existing media if no new media is uploaded
            Map<String, Object> existingContent = elasticsearchService.getContentById(id);
            if (existingContent.containsKey("mediaUrl")) {
                updatedContent.put("mediaUrl", existingContent.get("mediaUrl"));
                updatedContent.put("mediaType", existingContent.get("mediaType"));
            }
        }
        
        elasticsearchService.updateContent(id, updatedContent);
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
        Map<String, Object> updatedContent = new HashMap<>(content);
        updatedContent.put("last_posted_date", Instant.now().toString());
        elasticsearchService.updateContent(id, updatedContent);

        return "redirect:/content";
    }

    @GetMapping("/create")
    public String showCreateForm() {
        return "content-create";
    }

    @PostMapping("/create")
    public String createContent(@RequestParam String text, 
                                @RequestParam(required = false) MultipartFile mediaFile,
                                @RequestParam(defaultValue = "false") boolean useAI) throws IOException {
        logger.info("Creating content with text: {}, mediaFile present: {}, useAI: {}", 
                    text, (mediaFile != null), useAI);

        String imageUrl = null;

        if (useAI) {
            logger.info("Processing content with AI");
            Map<String, String> fetchedContent = elasticsearchOpenAIService.processContent(text);
            text = fetchedContent.get("content");
            imageUrl = fetchedContent.get("imageUrl");

        }

        // Add this log statement
        logger.info("After AI processing, imageUrl is: {}", imageUrl);

        Map<String, Object> content = new HashMap<>();
        content.put("text", text);

        if (mediaFile != null && !mediaFile.isEmpty()) {
            logger.info("Processing uploaded media file: name={}, size={}, contentType={}", 
                        mediaFile.getOriginalFilename(), mediaFile.getSize(), mediaFile.getContentType());
            
            String mediaUrl = elasticsearchService.uploadMedia(mediaFile);
            logger.info("Media uploaded successfully. Media URL: {}", mediaUrl);
            
            content.put("mediaUrl", mediaUrl);
            String mediaType = mediaFile.getContentType().startsWith("image/") ? "image" : "video";
            content.put("mediaType", mediaType);
            logger.info("Media type determined: {}", mediaType);
        } else if (imageUrl != null) {
            logger.info("Attempting to download image from URL: {}", imageUrl);
            
            // Download the image and create a MultipartFile
            MultipartFile imageFile = downloadImage(imageUrl);
            if (imageFile != null) {
                logger.info("Image downloaded successfully: name={}, size={}, contentType={}", 
                            imageFile.getOriginalFilename(), imageFile.getSize(), imageFile.getContentType());
                
                String mediaUrl = elasticsearchService.uploadMedia(imageFile);
                logger.info("Downloaded image uploaded successfully. Media URL: {}", mediaUrl);
                
                content.put("mediaUrl", mediaUrl);
                content.put("mediaType", "image");
            } else {
                logger.warn("Failed to download image from URL: {}", imageUrl);
            }
        } else {
            logger.info("No media file or image URL provided");
        }

        logger.info("Final content map: {}", content);

        // Assuming you're indexing the content here
        elasticsearchService.indexContent(content);
        logger.info("Content indexed successfully");

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

    @GetMapping("/media/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        try {
            Path file = Paths.get(mediaUploadDir).resolve(filename).normalize();
            Resource resource = new UrlResource(file.toUri());
            
            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(file);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
            } else {
                throw new RuntimeException("Could not read the file!");
            }
        } catch (IOException e) {
            throw new RuntimeException("Error: " + e.getMessage());
        }
    }

    private MultipartFile downloadImage(String imageUrl) {
        logger.info("Attempting to download image from URL: {}", imageUrl);

        try {
            URL url = new URL(imageUrl);
            String fileName = Paths.get(url.getPath()).getFileName().toString();
            logger.debug("Extracted file name: {}", fileName);

            String contentType = URLConnection.guessContentTypeFromName(fileName);
            logger.debug("Guessed content type: {}", contentType);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = url.openStream()) {
                int n;
                byte[] buffer = new byte[1024];
                long totalBytesRead = 0;
                while ((n = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, n);
                    totalBytesRead += n;
                }
                logger.debug("Total bytes read: {}", totalBytesRead);
            }

            byte[] content = baos.toByteArray();
            logger.info("Image downloaded successfully. Size: {} bytes", content.length);

            // Use our custom ByteArrayMultipartFile
            MultipartFile multipartFile = new ByteArrayMultipartFile(content, "file", fileName, contentType);
            logger.info("Created MultipartFile: name={}, originalFilename={}, contentType={}, size={}", 
                        multipartFile.getName(), multipartFile.getOriginalFilename(), 
                        multipartFile.getContentType(), multipartFile.getSize());

            return multipartFile;

        } catch (IOException e) {
            logger.error("Failed to download image from URL: {}. Error: {}", imageUrl, e.getMessage(), e);
            return null;
        }
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<String> handleMultipartException(MultipartException e) {
        logger.error("File upload error: " + e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                             .body("File upload failed: The file size exceeds the maximum allowed size.");
    }
}