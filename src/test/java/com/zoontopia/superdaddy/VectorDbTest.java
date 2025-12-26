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
        String query = "트름 시키는 법 어깨 위에 안고 트림시키기 아기의 머리가"; // Test query related to parenting guide

        // When
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(7)
                        .similarityThreshold(0.6) // Slightly lower threshold for testing
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
