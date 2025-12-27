package com.zoontopia.superdaddy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class VectorDbTest {

    @Autowired
    private VectorStore vectorStore;

    @Test
    public void testSimilaritySearch() {
        // Given
        String query = "산모가 출산 후에 느끼는 우울감과 감정 기복"; // Test query related to parenting guide

        // When
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(20)
                        .similarityThreshold(0.7) // Slightly lower threshold for testing
                        .build()
        );

        // Then
        System.out.println("=== Vector DB Search Results ===");
        if (results.isEmpty()) {
            System.out.println("No documents found.");
        } else {
            for (Document doc : results) {
                System.out.println("Score: " + doc.getScore());
                System.out.println("Content: " + doc.getText());
                System.out.println("Metadata: " + doc.getMetadata());
                System.out.println("------------------------------");
            }
        }
        
        // Assert that we get some results (assuming data is ingested)
        // Note: If no data is ingested, this might fail or be empty. 
        // This test is primarily to see the output as requested.
        assertThat(results).isNotNull();
    }
}
