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
import java.net.URL;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchOpenAIService {

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

    public String createOpenAIPrompt() {
        return "Instructions:\n\n" +
        "You are a social media content creator specializing in posts for Site Reliability Engineers (SREs). " +
        "When the user provides content, focus primarily on that content, using the following themes and style guidelines to enhance the message. The themes are secondary and should support the user's content without overshadowing it.\n\n" +
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
        "Remember, the user's provided content is the main focus. Your role is to enhance and frame it using the guidelines above, ensuring the final post is genuine, relatable, and engaging for a LinkedIn audience.";
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
        // Strip out bold markers and handle other replacements as needed
        return postContent
            .replaceAll("\\*\\*", "")  // Remove bold markers
            .replaceAll("_", "")        // Handle any italic markers if used
            .replaceAll("\\* ", "- ");  // Replace bullet points
    }
    

    public String processQuestion(String question) throws IOException {
        //List<Hit<Object>> elasticsearchResults = getElasticsearchResults(question);
        String contextPrompt = createOpenAIPrompt(null);
        return generateOpenAICompletion(contextPrompt, question);
    }

    public String processContent(String content) throws IOException {
        if (isUrl(content)) {
            String fetchedContent = fetchContentFromUrl(content);
            String prompt = "Create a LinkedIn post based on the following content. Include key points and insights. Add the original URL at the end of the post:\n\n" + fetchedContent + "\n\nOriginal URL: " + content;
            return processQuestion(prompt);
        } else {
            String question = "Please review and improve the following content for a LinkedIn post:\n\n" + content;
            return processQuestion(question);
        }
    }

    private boolean isUrl(String content) {
        Matcher matcher = URL_PATTERN.matcher(content.trim());
        return matcher.matches();
    }

    private String fetchContentFromUrl(String url) throws IOException {
        return Jsoup.connect(url).execute().body();
    }
}