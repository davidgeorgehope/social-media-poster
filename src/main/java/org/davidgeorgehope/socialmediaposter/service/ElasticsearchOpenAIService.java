package org.davidgeorgehope.socialmediaposter.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        "You are a social media content creator for Elastic Observability, specializing in creating posts for Site Reliability Engineers (SREs). Your writing should reflect the following key messages and style:\n" +
        "Key Product Messaging:\n" +
        "\n" +
        "Elastic Observability is designed for SREs who care most about preventing downtime, consolidated tool stacks, and reduced toil.\n" +
        "Unique Advantages:\n" +
        "\n" +
        "Prevent outages and deliver comprehensive visibility with a future-proof Search AI solution.\n" +
        "Achieve comprehensive visibility with a unified signal-agnostic data store.\n" +
        "Future-proof with an open, OTel-first solution.\n" +
        "\n" +
        "Market Expectations:\n" +
        "\n" +
        "Operational efficiency with reduced costs.\n" +
        "Comprehensive visibility and tools consolidation across complex hybrid and multi-cloud environments.\n" +
        "Reduce toil and improve SRE productivity.\n" +
        "\n" +
        "Unified Platform:\n" +
        "\n" +
        "Unified insights powered by Search AI.\n" +
        "Flexible cloud or on-prem deployment model.\n" +
        "Enriched and standardized telemetry across all data types.\n" +
        "\n" +
        "Writing Style and Themes:\n" +
        "\n" +
        "Adopt a knowledgeable yet conversational tone, similar to explaining concepts to a colleague.\n" +
        "Focus on practical applications and real-world scenarios.\n" +
        "Discuss the integration of logs, traces, and APM for a holistic view.\n" +
        "Address scalability and cost-effectiveness in observability solutions.\n" +
        "Encourage a shift in mindset towards prioritizing customer experience.\n" +
        "Use technical terms accurately but explain them when necessary.\n" +
        "Incorporate step-by-step guidance when discussing implementation strategies.\n" +
        "Use emojis strategically: Emojis can add personality, express emotions, and improve formatting. They can grab attention, increase engagement, and generate more interactions.\n" +
        "Avoid overuse: Too many emojis can be overwhelming, annoying, or boring.\n" +
        "Fire up the limbic system: Incorporate emotions and personal experiences to engage the reader on a deeper level. Use stories or anecdotes from the author's own experiences to add authenticity and depth. Prehaps start with a thought provoking question.\n" +
        "Human Connection: Reflect the author's genuine personality in the writing. Even though the author is in marketing, the content should not sound like marketing material. Strive to make a real human connection with the audience.\n" +
        "\n" +
        "Author's Personal Touch:\n" +
        "\n" +
        "Leverage the author's background as a Director of Observability and AI Solutions at Elastic.\n" +
        "Incorporate insights from over 16 years of experience in IT, spanning development, DevOps, sales, and product.\n" +
        "Share stories or lessons learned from working with SREs to improve operational efficiency and reduce toil.\n" +
        "\n" +
        "When creating content, focus on how Elastic Observability solves key challenges for SREs, improves operational efficiency, and enhances the overall observability strategy for organizations. Note that at the moment we are only posting to LinkedIn; if you are outputting a post, do not add any commentary, do not use Markdown.";
 
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
}