package com.zoontopia.superdaddy.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Node("Chunk")
@Getter
@Setter
@NoArgsConstructor
public class ChunkNode {

    @Id
    @GeneratedValue
    private Long id;

    private String content;

    // Vector Embedding (Floats)
    private List<Float> embedding;

    @Relationship(type = "MENTIONS", direction = Relationship.Direction.OUTGOING)
    private List<EntityNode> mentions = new ArrayList<>();

    public ChunkNode(String content, List<Float> embedding) {
        this.content = content;
        this.embedding = embedding;
    }
    
    public void addMention(EntityNode entityNode) {
        this.mentions.add(entityNode);
    }
}
