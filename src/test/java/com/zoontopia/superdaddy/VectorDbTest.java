package com.zoontopia.superdaddy;

import com.zoontopia.superdaddy.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class VectorDbTest {

    @Autowired
    private VectorStore vectorStore;

    // ChatClient 대신 Builder를 주입받습니다.
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    @Autowired
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        // 테스트 시작 전 ChatClient를 직접 생성
        this.chatClient = chatClientBuilder.build();
    }

    @Test
    public void testSimilaritySearch() {
        // Given


        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        String query = "신생아 목욕순서나 적정 물온도 어떻게 되는지 알려줘";
        String instruction = "RETRIEVAL_QUERY: "; // 혹은 "질문에 답변하기 위한 가장 관련 있는 내용을 찾아주세요: "



        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(200)
                        //.similarityThreshold(0.4)
                        .build());

        // Then
        System.out.println("=== Vector DB Search Results ===");
        if (results.isEmpty()) {
            System.out.println("No documents found.");
        } else {
            for (Document doc : results) {
                System.out.println("Score: " + doc.getScore());
                System.out.println("Content: " + doc.getText());
                System.out.println("Metadata: " + doc.getMetadata());
                System.out.println("ParentDocId: " + doc.getMetadata().get("parent_document_id"));
                System.out.println("------------------------------");
            }
        }
        
        // Assert that we get some results (assuming data is ingested)
        // Note: If no data is ingested, this might fail or be empty. 
        // This test is primarily to see the output as requested.
        assertThat(results).isNotNull();
    }
}
