package com.example.agent.rag.split;

/** 切片参数快照，由 RagProperties 构造，保证切片纯函数不依赖 Spring 配置。 */
public record ChunkParams(int parentTargetChars, int parentMaxChars, int parentMinChars,
                          int childTargetChars, int childMaxChars, int childMinChars,
                          int childOverlapChars, int childMaxPerParent, int keywordLimit) {

    public ChunkParams {
        if (parentMaxChars < parentTargetChars) {
            throw new IllegalArgumentException("parentMaxChars 不能小于 parentTargetChars");
        }
        if (childMaxChars < childTargetChars) {
            throw new IllegalArgumentException("childMaxChars 不能小于 childTargetChars");
        }
        if (childOverlapChars < 0 || childOverlapChars >= childTargetChars) {
            throw new IllegalArgumentException("childOverlapChars 必须大于等于 0 且小于 childTargetChars");
        }
        if (childMaxPerParent < 1) {
            throw new IllegalArgumentException("childMaxPerParent 必须大于等于 1");
        }
    }
}
