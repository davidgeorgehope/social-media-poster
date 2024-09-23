package org.davidgeorgehope.socialmediaposter;

import java.time.Duration;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import com.theokanning.openai.service.OpenAiService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

@SpringBootApplication
@ComponentScan(basePackages = "org.davidgeorgehope.socialmediaposter")
@EnableScheduling
public class SocialMediaPosterApplication {

        private static final Logger log = LoggerFactory.getLogger(SocialMediaPosterApplication.class);

    @Value("${elasticsearch.host}")
    private String elasticsearchHost;

    @Value("${elasticsearch.port}")
    private int elasticsearchPort;

    @Value("${elasticsearch.api-key}")
    private String elasticsearchApiKey;

    @Value("${openai.api-key}")
    private String openaiApiKey;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // Remove this log statement to avoid exposing the API key in logs
        // log.error("KEY"+elasticsearchApiKey);

        // Create the low-level client
        RestClient restClient = RestClient.builder(
                new HttpHost(elasticsearchHost, elasticsearchPort, "https"))
                .setDefaultHeaders(new Header[]{
                        new BasicHeader("Authorization", "ApiKey " + elasticsearchApiKey)
                })
                .build();

        // Create the transport with a Jackson mapper
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        // And create the API client
        return new ElasticsearchClient(transport);
    }
    @Bean
    public RestTemplate restTemplate() {
        log.error("KEY"+elasticsearchApiKey);

        return new RestTemplate();
    }


    @Bean
    public OpenAiService openAiService() {
        return new OpenAiService(openaiApiKey, Duration.ofSeconds(30));
    }

    public static void main(String[] args) {
        SpringApplication.run(SocialMediaPosterApplication.class, args);
    }

    
}
