package org.davidgeorgehope.socialmediaposter.controller;

import org.davidgeorgehope.socialmediaposter.service.LinkedInService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.util.Collections;

@RestController
@RequestMapping("/api/linkedin")
public class LinkedInController {
    private static final Logger logger = LoggerFactory.getLogger(LinkedInController.class);

    private final LinkedInService linkedInService;

    @Autowired
    public LinkedInController(LinkedInService linkedInService) {
        this.linkedInService = linkedInService;
    }

    @GetMapping("/check-token")
    public ResponseEntity<Map<String, Boolean>> checkToken(@RequestParam String email) {
        boolean isValid = linkedInService.hasValidAccessToken(email);
        Map<String, Boolean> response = Collections.singletonMap("valid", isValid);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/initial-tokens")
    public ResponseEntity<String> getInitialTokens(@RequestParam String code, @RequestParam String redirectUri, @RequestParam String email) {
        try {
            String result = linkedInService.getInitialTokens(code, redirectUri, email);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error getting initial tokens", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Error getting initial tokens: " + e.getMessage());
        }
    }

}