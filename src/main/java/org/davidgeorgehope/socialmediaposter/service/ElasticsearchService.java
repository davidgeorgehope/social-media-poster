package org.davidgeorgehope.socialmediaposter.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class ElasticsearchService {

    private final ElasticsearchClient esClient;

    @Value("${media.upload.dir}")
    private String mediaUploadDir;

    public String uploadMedia(MultipartFile file) throws IOException {
        String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
        Path filePath = Paths.get(mediaUploadDir, fileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        return mediaUploadDir + fileName;
    }

    @Autowired
    public ElasticsearchService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    // Fetch content from Elasticsearch index
    public List<Map<String, Object>> getContentFromIndex(int page, int size) throws IOException {
        SearchResponse<Map<String, Object>> response = esClient.search(s -> s
                .index("social-pilot-content")
                .from((page - 1) * size)
                .size(size)
                .sort(sort -> sort
                    .field(f -> f
                        .field("last_posted_date")
                        .order(SortOrder.Desc)
                        .missing("_last")
                    )
                ),
                (Class<Map<String, Object>>)(Class<?>)Map.class
        );

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
        // Create a new map to avoid modifying the original content
        Map<String, Object> updateContent = new HashMap<>(content);
        
        // Remove _id from the content if it exists
        updateContent.remove("_id");
        
        // Add last_updated field
        updateContent.put("last_updated", Instant.now().toString());
        
        esClient.update(u -> u
                .index("social-pilot-content")
                .id(id)
                .doc(updateContent),
                (Class<Map<String, Object>>)(Class<?>)Map.class // Fix for the generic Map type
        );
    }

    public List<Map<String, Object>> getContentForScheduling() throws IOException {
        SearchResponse<Map<String, Object>> response = esClient.search(s -> s
                .index("social-pilot-content")
                .size(100) // Adjust size as needed
                .sort(sort -> sort
                    .field(f -> f
                        .field("last_posted_date")
                        .order(SortOrder.Asc)
                        .missing("_first")
                    )
                ),
                (Class<Map<String, Object>>)(Class<?>)Map.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> sourceWithId = hit.source();
                    sourceWithId.put("_id", hit.id());
                    return sourceWithId;
                })
                .collect(Collectors.toList());
    }

    public String indexContent(Map<String, Object> content) throws IOException {
        content.put("last_updated", Instant.now().toString());

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonDocument = objectMapper.writeValueAsString(content);

        var response = esClient.index(i -> i
                .index("social-pilot-content")
                .withJson(new StringReader(jsonDocument))
        );

        return response.id();
    }

    public void deleteContent(String id) throws IOException {
        DeleteRequest deleteRequest = DeleteRequest.of(d -> d
            .index("social-pilot-content")
            .id(id)
        );

        esClient.delete(deleteRequest);
    }

    public Map<String, Object> getContentById(String id) throws IOException {
        GetResponse<Map<String, Object>> response = esClient.get(g -> g
                .index("social-pilot-content")
                .id(id),
                (Class<Map<String, Object>>)(Class<?>)Map.class
        );

        if (response.found()) {
            Map<String, Object> source = response.source();
            source.put("_id", response.id());
            return source;
        } else {
            throw new RuntimeException("Content not found for id: " + id);
        }
    }

    // Add a new method to get the total number of documents
    public long getTotalContentCount() throws IOException {
        SearchResponse<Map<String, Object>> response = esClient.search(s -> s
                .index("social-pilot-content")
                .size(0),
                (Class<Map<String, Object>>)(Class<?>)Map.class
        );

        return response.hits().total().value();
    }
}
