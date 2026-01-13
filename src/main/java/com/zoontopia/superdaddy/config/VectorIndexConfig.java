package com.zoontopia.superdaddy.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class VectorIndexConfig {

    private final Neo4jClient neo4jClient;

    @PostConstruct
    public void createVectorIndex() {
        log.info("Creating vector index 'chunk_embedding_index' if it does not exist...");

        String query = """
                CREATE VECTOR INDEX chunk_embedding_index IF NOT EXISTS
                FOR (c:Chunk)
                ON (c.embedding)
                OPTIONS {indexConfig: {
                 `vector.dimensions`: 768,
                 `vector.similarity_function`: 'cosine'
                }}
                """;

        try {
            neo4jClient.query(query).run();
            log.info("Vector index check/creation completed successfully.");
        } catch (Exception e) {
            log.error("Failed to create vector index: {}", e.getMessage());
            // 이미 존재하거나 다른 이슈일 수 있으므로 로그만 남기고 앱 실행은 계속 진행
        }
    }
}
