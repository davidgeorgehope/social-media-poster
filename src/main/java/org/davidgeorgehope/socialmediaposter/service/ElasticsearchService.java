package org.davidgeorgehope.socialmediaposter.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.DeleteRequest;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class ElasticsearchService {

    private final ElasticsearchClient esClient;

    @Autowired
    public ElasticsearchService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    // Fetch content from Elasticsearch index
    public List<Map<String, Object>> getContentFromIndex() throws IOException {
        SearchResponse<Map<String, Object>> response = esClient.search(s -> s
                .index("social-pilot-content")
                .size(10), // Adjust size as needed
                (Class<Map<String, Object>>)(Class<?>)Map.class
        );

        // Return a list of maps that include both the document ID and its content
        return response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> sourceWithId = hit.source();
                    sourceWithId.put("_id", hit.id());
                    return sourceWithId;
                })
                .collect(Collectors.toList());
    }

    // Update document in Elasticsearch
    public void updateContent(String id, Map<String, Object> content) throws IOException {
        esClient.update(u -> u
                .index("social-pilot-content")
                .id(id)
                .doc(content),
                (Class<Map<String, Object>>)(Class<?>)Map.class // Fix for the generic Map type
        );
    }

    public List<Map<String, Object>> getContentForScheduling() throws IOException {
        Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        
        SearchResponse<Map<String, Object>> response = esClient.search(s -> s
                .index("social-pilot-content")
                .size(50) // Increased size to have more options
                .query(q -> q
                    .bool(b -> b
                        .mustNot(mn -> mn
                            .range(r -> r
                                .field("last_posted_date")
                                .gte(JsonData.of(oneWeekAgo.toString()))
                            )
                        )
                    )
                )
                .sort(sort -> sort
                    .field(f -> f
                        .field("last_posted_date")
                        .order(SortOrder.Asc)
                    )
                ),
                (Class<Map<String, Object>>)(Class<?>)Map.class
        );

        // Return a list of maps that include both the document ID and its content
        return response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> sourceWithId = hit.source();
                    sourceWithId.put("_id", hit.id());
                    return sourceWithId;
                })
                .collect(Collectors.toList());
    }

    public void indexContent(String text) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> document = new HashMap<>();
        document.put("text", text);

        // Serialize the document map to a JSON string
        String jsonDocument = objectMapper.writeValueAsString(document);

        // Ensure the request body is correctly formatted as JSON
        esClient.index(i -> i
                .index("social-pilot-content")
                .withJson(new StringReader(jsonDocument))  // Sends JSON directly
        );
    }

    public void deleteContent(String id) throws IOException {
        DeleteRequest deleteRequest = DeleteRequest.of(d -> d
            .index("social-pilot-content")
            .id(id)
        );

        esClient.delete(deleteRequest);
    }
}
