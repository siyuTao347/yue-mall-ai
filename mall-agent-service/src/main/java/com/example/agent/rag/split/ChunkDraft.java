package com.example.agent.rag.split;

/**
 * 分片草稿（未落库）。
 *
 * @param level       1=父片 2=子片
 * @param chunkNo     分片编号，如 PROD-001-v1-P003-C02
 * @param chunkHash   内容哈希，落库唯一约束用
 * @param titlePath   标题路径
 * @param content     分片正文
 * @param charStart   在归一化正文中的起始偏移
 * @param charEnd     结束偏移（不含）
 * @param keywordsJson 关键词与权重 JSON
 * @param splitForced  是否触发了硬切分
 * @param tokenCount   估算 token 数
 */
public record ChunkDraft(int level, String chunkNo, String chunkHash, String titlePath, String content,
                         int charStart, int charEnd, String keywordsJson, boolean splitForced,
                         int tokenCount) {

    public int charCount() {
        return content.length();
    }
}
