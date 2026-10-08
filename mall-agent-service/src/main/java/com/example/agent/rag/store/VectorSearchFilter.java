package com.example.agent.rag.store;

import java.util.List;

/** 向量检索过滤条件。 */
public record VectorSearchFilter(List<String> docTypes, String model, int dim, String status) {

    public static VectorSearchFilter of(List<String> docTypes, String model, int dim) {
        return new VectorSearchFilter(docTypes, model, dim, "READY");
    }

    public boolean hasDocTypes() {
        return docTypes != null && !docTypes.isEmpty();
    }
}
