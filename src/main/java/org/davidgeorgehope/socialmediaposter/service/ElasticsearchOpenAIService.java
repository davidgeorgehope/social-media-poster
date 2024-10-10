package org.davidgeorgehope.socialmediaposter.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchOpenAIService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchOpenAIService.class);
    private final ElasticsearchClient esClient;
    private final AICompletionService aiCompletionService;

    private static final Map<String, List<String>> INDEX_SOURCE_FIELDS = new HashMap<>();
    static {
        INDEX_SOURCE_FIELDS.put("social-pilot-content", List.of("text"));
    }

    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://)?[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,4}(/\\S*)?$");

    @Autowired
    public ElasticsearchOpenAIService(ElasticsearchClient esClient, AICompletionService aiCompletionService) {
        this.esClient = esClient;
        this.aiCompletionService = aiCompletionService;
    }

    public List<Hit<Object>> getElasticsearchResults(String query) throws IOException {
        String esQuery = """
        {
          "query": {
            "nested": {
              "path": "text.inference.chunks",
              "query": {
                "sparse_vector": {
                  "inference_id": "social-pilot-inference",
                  "field": "text.inference.chunks.embeddings",
                  "query": "%s"
                }
              },
              "inner_hits": {
                "size": 2,
                "name": "social-pilot-content.text",
                "_source": {
                  "includes": ["text.inference.chunks.text"]
                }
              }
            }
          },
          "size": 3
        }
        """.formatted(query);

        SearchResponse<Object> response = esClient.search(s -> s
            .index("social-pilot-content")
            .withJson(new StringReader(esQuery)),
            Object.class
        );

        return response.hits().hits();
    }

    private String buildContextFromHits(List<Hit<Object>> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        for (Hit<Object> hit : results) {
            String indexName = hit.index();
            if (indexName != null && INDEX_SOURCE_FIELDS.containsKey(indexName)) {
                String sourceField = INDEX_SOURCE_FIELDS.get(indexName).get(0);
                String innerHitPath = indexName + "." + sourceField;
                
                if (hit.innerHits() != null && hit.innerHits().containsKey(innerHitPath)) {
                    hit.innerHits().get(innerHitPath).hits().hits().stream()
                        .map(Hit::source)
                        .filter(Map.class::isInstance)
                        .map(source -> ((Map<?, ?>) source).get("text"))
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .forEach(text -> context.append(text).append("\n --- \n"));
                } else if (hit.source() instanceof Map) {
                    Object sourceValue = ((Map<?, ?>) hit.source()).get(sourceField);
                    if (sourceValue instanceof String) {
                        context.append((String) sourceValue).append("\n");
                    }
                }
            }
        }
        return context.toString();
    }

    public String createOpenAIPrompt(List<Hit<Object>> results) {
        String context = buildContextFromHits(results);
        return "You are a social media content creator specializing in posts for Site Reliability Engineers (SREs). Your task is to create engaging LinkedIn posts based on provided content while adhering to specific guidelines and themes.\n\n" +
        "The content will be provided in the user prompt. Your primary focus should be on this content. Use the following guidelines to enhance and frame the message without overshadowing it:\n\n" +
        "1. Adopt a knowledgeable yet conversational tone, as if explaining concepts to a colleague.\n" +
        "2. Begin with a thought-provoking question or personal anecdote when appropriate.\n" +
        "3. Focus on real-world scenarios and practical applications.\n" +
        "4. Share insights or lessons learned from experience working with SREs.\n" +
        "5. Discuss challenges in observability and how they can be addressed.\n" +
        "6. Use technical terms accurately and explain them when necessary.\n" +
        "7. Provide actionable advice or step-by-step guidance.\n" +
        "8. Use emojis sparingly to add personality or for formatting where appropriate.\n" +
        "9. Avoid marketing language; strive for authenticity and human connection.\n\n" +
        "Incorporate the following themes if relevant to the user's content:\n" +
        "- How Elastic Observability can solve key challenges for SREs.\n" +
        "- Improving operational efficiency and reducing toil.\n" +
        "- Enhancing observability strategies in organizations.\n\n" +
        "Important guidelines:\n" +
        "- If a blog post is provided do not include too much detail from the source content. Provide just enough information to pique the reader's interest and encourage them to read the full blog post.\n" +
        "- Use plain language and avoid buzzwords or jargon.\n" +
        "- The user's provided content is the main focus. Your role is to enhance and frame it using the guidelines above.\n\n" +
        "When crafting your post:\n" +
        "1. First, analyze the user's content and identify the key points and themes.\n" +
        "2. Think about how to present these points in an engaging way for a LinkedIn audience.\n" +
        "3. Consider which of the provided themes and guidelines are most relevant to the content.\n" +
        "4. Draft your post, ensuring it aligns with the writing guidelines and incorporates relevant themes.\n" +
        "5. Review your draft to ensure it focuses primarily on the user's content and doesn't overshadow it with additional information.\n\n" +
        "Take a step back and reflect carefully on how best to solve your task"+
        "IMPORTANT: Output ONLY the final post. Do not include any explanations, notes, or commentary before or after the post. Do not use Markdown formatting. Do not use any tags in your output.\n" ;
    }
    
    

    public String generateOpenAICompletion(String userPrompt, String question) {
        String response = aiCompletionService.generateCompletion(userPrompt, question);
        return formatForLinkedIn(response);
    }

    public String formatForLinkedIn(String postContent) {
        return postContent
            .replaceAll("\\*\\*", "")  // Remove bold markers
            .replaceAll("_", "")        // Handle any italic markers if used
            .replaceAll("\\* ", "- ")   // Replace bullet points
            .replaceAll("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)", "$2"); // Remove markdown links, keep URL
    }
    

    public String processQuestion(String question) throws IOException {
        //List<Hit<Object>> elasticsearchResults = getElasticsearchResults(question);
        String contextPrompt = createOpenAIPrompt(null);
        return generateOpenAICompletion(contextPrompt, question);
    }

    public String processAssistantQuestion(String question) throws IOException {
        //List<Hit<Object>> elasticsearchResults = getElasticsearchResults(question);
        String contextPrompt = "You are a social media content creator AI Assistant specializing in helping a user make posts for Site Reliability Engineers (SREs)"+
        "Writing Guidelines:\n" +
        "- Adopt a knowledgeable yet conversational tone, as if explaining concepts to a colleague.\n" +
        "- Begin with a thought-provoking question or personal anecdote when appropriate.\n" +
        "- Focus on real-world scenarios and practical applications.\n" +
        "- Share insights or lessons learned from experience working with SREs.\n" +
        "- Discuss challenges in observability and how they can be addressed.\n" +
        "- Use technical terms accurately and explain them when necessary.\n" +
        "- Provide actionable advice or step-by-step guidance.\n" +
        "- Use emojis sparingly to add personality or for formatting where appropriate.\n" +
        "- Avoid marketing language; strive for authenticity and human connection.\n\n" +
        "Secondary Themes (to incorporate if relevant):\n" +
        "- How Elastic Observability can solve key challenges for SREs.\n" +
        "- Improving operational efficiency and reducing toil.\n" +
        "- Enhancing observability strategies in organizations.\n\n";
 
        return generateOpenAICompletion(contextPrompt, question);
    }

    public Map<String, String> processContent(String content) throws IOException {
        if (isUrl(content)) {
            Map<String, String> fetchedContent = fetchContentFromUrl(content);
            String fetchedText = fetchedContent.get("content");
            String imageUrl = fetchedContent.get("imageUrl");

            String prompt = "Create a LinkedIn post based on the following content. Include key points and insights. Add the original URL at the end of the post:\n\n" + fetchedText + "\n\nOriginal URL: " + content;

            String generatedContent = processQuestion(prompt);
            fetchedContent.put("content", generatedContent);

            // Handle the imageUrl (e.g., store it or pass it along with the content)
            // You might need to adjust your application to accept and process the image URL

            // For now, return both the content and image URL (you can adjust this as needed)
            return fetchedContent;
        } else {
            String question = "Please review and improve the following content for a LinkedIn post:\n\n" + content;
            Map<String, String> fetchedContent = new HashMap<>();
            fetchedContent.put("content", processQuestion(question));
            return fetchedContent;
        }
    }

    public boolean isUrl(String input) {
        Matcher matcher = URL_PATTERN.matcher(input.trim());
        return matcher.matches();
    }

    public Map<String, String> fetchContentFromUrl(String url) throws IOException {
        logger.info("Fetching content from URL: {}", url);

        Document doc = Jsoup.connect(url).get();
        String textContent = doc.body().text();
        logger.debug("Fetched text content (first 100 chars): {}", textContent.substring(0, Math.min(textContent.length(), 100)));

        // Extract image from twitter:image meta tag
        String imgUrl = null;
        Element twitterImageMeta = doc.select("meta[name=twitter:image]").first();
        if (twitterImageMeta != null) {
            imgUrl = twitterImageMeta.attr("content");
            logger.info("Found twitter:image: {}", imgUrl);
        } else {
            logger.info("No twitter:image meta tag found");
        }

        // Fallback to previous image selection logic if twitter:image is not found
        if (imgUrl == null) {
            // Extract the first image larger than 200x200 pixels
            Elements imgElements = doc.select("img");
            for (Element img : imgElements) {
                String width = img.attr("width");
                String height = img.attr("height");
                
                if (!width.isEmpty() && !height.isEmpty()) {
                    try {
                        int w = Integer.parseInt(width);
                        int h = Integer.parseInt(height);
                        
                        if (w > 200 && h > 200) {
                            imgUrl = img.absUrl("src");
                            logger.info("Selected first image larger than 200x200: {} ({}x{})", imgUrl, w, h);
                            break;
                        }
                    } catch (NumberFormatException e) {
                        logger.warn("Failed to parse image dimensions for: {}", img.outerHtml());
                    }
                }
            }
        }

        if (imgUrl == null) {
            // Fallback to first image if no suitable image is found
            Elements imgElements = doc.select("img"); // Add this line
            Element firstImg = imgElements.first();
            if (firstImg != null) {
                imgUrl = firstImg.absUrl("src");
                logger.info("No image larger than 200x200 found. Defaulting to first image: {}", imgUrl);
            } else {
                logger.info("No images found on the page");
            }
        }

        Map<String, String> result = new HashMap<>();
        result.put("content", textContent);
        result.put("imageUrl", imgUrl);

        logger.info("Fetched content successfully. Image URL: {}", imgUrl);
        return result;
    }
}