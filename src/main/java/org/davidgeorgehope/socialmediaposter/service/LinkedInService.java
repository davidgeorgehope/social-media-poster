package org.davidgeorgehope.socialmediaposter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;


import com.fasterxml.jackson.databind.JsonNode;


import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.nio.file.Path;

@Service
public class LinkedInService {

    private static final Logger logger = LoggerFactory.getLogger(LinkedInService.class);
    
    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DATA_SEPARATOR = ",";
    private static final long TOKEN_EXPIRY_BUFFER = 300; // 5 minutes buffer

    public LinkedInService(RestTemplate restTemplate,
                           @Value("${linkedin.client-id}") String clientId,
                           @Value("${linkedin.client-secret}") String clientSecret) {
        logger.info("Initializing LinkedInService");
        if (restTemplate == null) {
            logger.error("RestTemplate is null");
            throw new IllegalArgumentException("RestTemplate cannot be null");
        }
        if (clientId == null || clientId.isEmpty()) {
            logger.error("Client ID is null or empty");
            throw new IllegalArgumentException("Client ID cannot be null or empty");
        }
        if (clientSecret == null || clientSecret.isEmpty()) {
            logger.error("Client Secret is null or empty");
            throw new IllegalArgumentException("Client Secret cannot be null or empty");
        }
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        logger.info("LinkedInService initialized successfully");
    }

    public String getInitialTokens(String authorizationCode, String redirectUri, String email) {
        logger.debug("Getting initial LinkedIn tokens for email: {}", email);
        
        // Check if we already have a valid token
        String[] existingUserData = getUserData(email);
        if (existingUserData != null && existingUserData.length >= 3 && !isTokenExpired(existingUserData[2])) {
            logger.info("Valid token already exists for email: {}", email);
            return "Existing valid token found for email: " + email;
        }

        String tokenUrl = "https://www.linkedin.com/oauth/v2/accessToken";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", authorizationCode);
        body.add("redirect_uri", redirectUri);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> responseBody = response.getBody();
                logger.debug("LinkedIn API response: {}", responseBody);

                String accessToken = (String) responseBody.get("access_token");
                long expiresIn = Long.parseLong(String.valueOf(responseBody.get("expires_in")));
                long expirationTime = System.currentTimeMillis() + (expiresIn * 1000);
                
                logger.debug("Access Token: {}", accessToken);
                logger.debug("Expires In: {} seconds", expiresIn);

                String memberId = null;
                try {
                    memberId = getMemberId(accessToken);
                } catch (Exception e) {
                    logger.warn("Failed to retrieve member ID. This is non-critical.", e);
                }

                storeUserData(email, accessToken, memberId, String.valueOf(expirationTime));

                return "Access token obtained and stored successfully for email: " + email + ". Expires in: " + expiresIn + " seconds";
            } else {
                logger.error("Failed to obtain tokens. Status code: {}", response.getStatusCode());
                throw new RuntimeException("Failed to obtain tokens");
            }
        } catch (Exception e) {
            logger.error("Error obtaining tokens", e);
            throw new RuntimeException("Failed to obtain tokens", e);
        }
    }

    private String getMemberId(String accessToken) {
        String apiUrl = "https://api.linkedin.com/v2/userinfo";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        
        HttpEntity<String> request = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(apiUrl, HttpMethod.GET, request, Map.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> responseBody = response.getBody();
                logger.error("Response Body: {}", responseBody);

                return (String) responseBody.get("sub");
            } else {
                logger.error("Failed to get member ID. Status code: {}", response.getStatusCode());
                throw new RuntimeException("Failed to get member ID");
            }
        } catch (Exception e) {
            logger.error("Error getting member ID", e);
            throw new RuntimeException("Failed to get member ID", e);
        }
    }

    private void storeUserData(String email, String accessToken, String memberId, String expirationTime) {
        if (email == null || email.isEmpty() || accessToken == null || accessToken.isEmpty()) {
            logger.warn("Attempted to store null or empty email or access token");
            return;
        }
        try {
            String data = accessToken + DATA_SEPARATOR + 
                          (memberId != null ? memberId : "") + DATA_SEPARATOR + 
                          expirationTime;
            Files.write(Paths.get(email + "_linkedin_data.txt"), data.getBytes());
            logger.info("User data stored successfully for email: {}", email);
        } catch (IOException e) {
            logger.error("Failed to store user data for email: {}", email, e);
            throw new RuntimeException("Failed to store user data", e);
        }
    }

    private String[] getUserData(String email) {
        try {
            Path path = Paths.get(email + "_linkedin_data.txt");
            if (Files.exists(path)) {
                String data = new String(Files.readAllBytes(path)).trim();
                return data.split(DATA_SEPARATOR);
            }
        } catch (IOException e) {
            logger.error("Failed to read user data for email: {}", email, e);
        }
        return null;
    }

    private boolean isTokenExpired(String expirationTimeStr) {
        try {
            long expirationTime = Long.parseLong(expirationTimeStr);
            return System.currentTimeMillis() > (expirationTime - TOKEN_EXPIRY_BUFFER * 1000);
        } catch (NumberFormatException e) {
            logger.error("Invalid expiration time format", e);
            return true; // Assume expired if we can't parse the time
        }
    }

    public boolean hasValidAccessToken(String email) {
        String[] userData = getUserData(email);
        return userData != null && userData.length >= 3 && !isTokenExpired(userData[2]);
    }

    public void postToLinkedIn(String postContent, String email, String mediaUrl, String mediaType) {
        logger.info("Attempting to post to LinkedIn for email: {}", email);
        String[] userData = getUserData(email);
        if (userData == null || userData.length == 0 || userData[0].isEmpty()) {
            throw new RuntimeException("No valid access token found for email: " + email);
        }
        String accessToken = userData[0];
        String memberId = userData.length > 1 ? userData[1] : null;

        String apiUrl = "https://api.linkedin.com/v2/ugcPosts";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("author", "urn:li:person:" + (memberId != null ? memberId : "me"));
        requestBody.put("lifecycleState", "PUBLISHED");
        
        ObjectNode shareContent = objectMapper.createObjectNode();
        shareContent.set("shareCommentary", objectMapper.createObjectNode().put("text", postContent));
        
        if (mediaUrl != null && !mediaUrl.isEmpty()) {
            shareContent.put("shareMediaCategory", mediaType.toUpperCase());
            ArrayNode media = shareContent.putArray("media");
            ObjectNode mediaNode = media.addObject();
            mediaNode.put("status", "READY");
            mediaNode.put("description", "text");
            try {
                mediaNode.set("media", objectMapper.createObjectNode()
                    .put("id", uploadMediaToLinkedIn(accessToken, memberId, mediaUrl, mediaType)));
            } catch (IOException e) {
                // Handle the exception, e.g., log it or rethrow it
                e.printStackTrace();
            }
        } else {
            shareContent.put("shareMediaCategory", "NONE");
        }

        ObjectNode specificContent = objectMapper.createObjectNode();
        specificContent.set("com.linkedin.ugc.ShareContent", shareContent);

        requestBody.set("specificContent", specificContent);
        requestBody.set("visibility", objectMapper.createObjectNode()
            .put("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC"));

        String requestBodyString = requestBody.toString();
        logger.debug("LinkedIn API request body: {}", requestBodyString);
        HttpEntity<String> request = new HttpEntity<>(requestBodyString, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Successfully posted to LinkedIn for email: {}", email);
            } else {
                logger.error("Failed to post to LinkedIn for email: {}. Status code: {}", email, response.getStatusCode());
                throw new RuntimeException("Failed to post to LinkedIn: " + response.getBody());
            }
        } catch (HttpStatusCodeException e) {
            HttpStatus statusCode = HttpStatus.valueOf(e.getStatusCode().value());
            String responseBody = e.getResponseBodyAsString();
            logger.error("LinkedIn API error for email: {}: Status Code: {}, Response Body: {}", email, statusCode, responseBody);
            logger.error("Request Headers: {}", headers);
            logger.error("Request Body: {}", requestBodyString);
            throw new RuntimeException("Failed to post to LinkedIn. Status: " + statusCode + ", Body: " + responseBody);
        } catch (Exception e) {
            logger.error("Unexpected error posting to LinkedIn for email: {}", email, e);
            throw new RuntimeException("Failed to post to LinkedIn", e);
        }
    }

    private String uploadMediaToLinkedIn(String accessToken, String memberId, String mediaUrl, String mediaType) throws IOException {
        // Step 1: Register the media with LinkedIn
        String registeredMediaId = registerMedia(accessToken, memberId, mediaType);

        // Step 2: Get the upload URL
        String uploadUrl = getUploadUrl(accessToken, registeredMediaId);

        // Step 3: Upload the media binary
        uploadMediaBinary(uploadUrl, mediaUrl);

        // Step 4: Confirm the upload
        String assetId = confirmMediaUpload(accessToken, registeredMediaId);

        return assetId;
    }

    private String registerMedia(String accessToken, String memberId, String mediaType) throws IOException {
        String apiUrl = "https://api.linkedin.com/v2/assets?action=registerUpload";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.set("registerUploadRequest", objectMapper.createObjectNode()
            .put("recipes", mediaType.equals("image") ? "urn:li:digitalmediaRecipe:feedshare-image" : "urn:li:digitalmediaRecipe:feedshare-video")
            .put("owner", "urn:li:person:" + memberId)
            .set("serviceRelationships", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                    .put("relationshipType", "OWNER")
                    .put("identifier", "urn:li:userGeneratedContent"))));

        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.POST, request, JsonNode.class);

        if (response.getStatusCode() == HttpStatus.OK) {
            return response.getBody().path("value").path("asset").asText();
        } else {
            throw new IOException("Failed to register media with LinkedIn");
        }
    }

    private String getUploadUrl(String accessToken, String registeredMediaId) throws IOException {
        String apiUrl = "https://api.linkedin.com/v2/assets/" + registeredMediaId + "?action=uploadUrl";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<String> request = new HttpEntity<>(headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.GET, request, JsonNode.class);

        if (response.getStatusCode() == HttpStatus.OK) {
            return response.getBody().path("value").path("uploadMechanism").path("com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest").path("uploadUrl").asText();
        } else {
            throw new IOException("Failed to get upload URL from LinkedIn");
        }
    }

    private void uploadMediaBinary(String uploadUrl, String mediaUrl) throws IOException {
        byte[] mediaBytes = Files.readAllBytes(Paths.get(mediaUrl));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        HttpEntity<byte[]> request = new HttpEntity<>(mediaBytes, headers);
        ResponseEntity<String> response = restTemplate.exchange(uploadUrl, HttpMethod.PUT, request, String.class);

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new IOException("Failed to upload media binary to LinkedIn");
        }
    }

    private String confirmMediaUpload(String accessToken, String registeredMediaId) throws IOException {
        String apiUrl = "https://api.linkedin.com/v2/assets/" + registeredMediaId;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.set("patch", objectMapper.createObjectNode()
            .set("$set", objectMapper.createObjectNode()
                .put("status", "AVAILABLE")));

        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.POST, request, JsonNode.class);

        if (response.getStatusCode() == HttpStatus.OK) {
            return registeredMediaId;
        } else {
            throw new IOException("Failed to confirm media upload with LinkedIn");
        }
    }
}