package org.davidgeorgehope.socialmediaposter.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OpenAiService openAiService;

    private static final Map<String, List<String>> INDEX_SOURCE_FIELDS = new HashMap<>();
    static {
        INDEX_SOURCE_FIELDS.put("social-pilot-content", List.of("text"));
    }

    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://)?[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,4}(/\\S*)?$");

    @Autowired
    public ElasticsearchOpenAIService(ElasticsearchClient esClient, OpenAiService openAiService) {
        this.esClient = esClient;
        this.openAiService = openAiService;
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
        return "Instructions:\n\n" +
        "You are a social media content creator specializing in posts for Site Reliability Engineers (SREs)." +
        "When the user provides content, focus primarily on that content, using the following themes and style guidelines to enhance the message. The themes are secondary and should support the user's content without overshadowing it.\n\n" +
        "**Important:** Do not include too much detail from the source content. Provide just enough information to pique the reader's interest and encourage them to read the full blog post.\n\n" +
        "**Important:** Do not use too many buzzwords or jargon. Use technical terms accurately and explain them when necessary.\n\n" +
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
        "- Enhancing observability strategies in organizations.\n\n" +
        "Remember, the user's provided content is the main focus. Your role is to enhance and frame it using the guidelines above, ensuring the final post is genuine, relatable, and engaging for a LinkedIn audience. VERY IMPORTANT: If you are outputting a post, do not add any commentary, do not use Markdown.";
    }
    
    

    public String generateOpenAICompletion(String userPrompt, String question) {
        ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(
                new ChatMessage("system", userPrompt),
                new ChatMessage("user", question)
            ))
            .build();

        String response = openAiService.createChatCompletion(completionRequest).getChoices().get(0).getMessage().getContent();
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