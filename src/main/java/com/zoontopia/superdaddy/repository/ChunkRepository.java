package com.zoontopia.superdaddy.repository;

import com.zoontopia.superdaddy.domain.entity.ChunkNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChunkRepository extends Neo4jRepository<ChunkNode, Long> {

    @Query("CALL db.index.vector.queryNodes('chunk_embedding_index', 10, $embedding) YIELD node, score " +
            "OPTIONAL MATCH (node)-[:MENTIONS]->(e:Entity) " +
            "WHERE e.name IN $keywords " +
            "WITH node, score, count(e) as matches " +
            "RETURN node " +
            "ORDER BY (score + (matches * 0.1)) DESC LIMIT 5")
    List<ChunkNode> findHybrid(@Param("embedding") List<Double> embedding, @Param("keywords") List<String> keywords);
}
