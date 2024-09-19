package org.davidgeorgehope.socialmediaposter.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


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
}
