package com.zoontopia.superdaddy.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Entity")
@Getter
@Setter
@NoArgsConstructor
public class EntityNode {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    public EntityNode(String name) {
        this.name = name;
    }
}
