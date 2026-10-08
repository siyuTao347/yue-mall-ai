package com.example.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 检索测试请求。
 * <p>本增量仅支持 {@code mode=VECTOR}（验证向量入库结果）；
 * KEYWORD / HYBRID 属于检索增量，传入会返回 RAG_RETRIEVAL_MODE_UNSUPPORTED。</p>
 */
public record VectorRetrievalTestRequest(
        @NotBlank(message = "query 不能为空") String query,
        String mode,
        List<String> docTypes,
        @Min(value = 1, message = "topK 最小为 1") @Max(value = 100, message = "topK 最大为 100") Integer topK,
        @Min(value = 1, message = "parentTopN 最小为 1") @Max(value = 20, message = "parentTopN 最大为 20")
        Integer parentTopN) {
}
