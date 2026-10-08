package com.example.agent.dto;

import java.util.Map;

/** 知识库概览。 */
public record KnowledgeOverviewDTO(long documentTotal, long documentEnabled, long parentChunkTotal,
                                  long childChunkTotal, long embeddedTotal, long embeddedFailed,
                                  long vectorCount, boolean vectorAvailable, String vectorVersion,
                                  Map<String, Long> indexStatusDistribution) {
}
