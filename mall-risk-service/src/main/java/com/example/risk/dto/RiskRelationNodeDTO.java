package com.example.risk.dto;

public record RiskRelationNodeDTO(
        String nodeType,
        Long nodeId,
        String nodeHash,
        String relationType,
        Integer weight,
        Integer degree
) {
}
