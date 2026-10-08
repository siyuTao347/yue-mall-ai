package com.example.risk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskDictionaryItemDTO(String code, String text, String tone, Integer sort,
                                    java.util.List<String> commands) {
}
