package com.zoontopia.superdaddy.repository;

import com.zoontopia.superdaddy.domain.entity.EntityNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EntityRepository extends Neo4jRepository<EntityNode, Long> {
    Optional<EntityNode> findByName(String name);
}
