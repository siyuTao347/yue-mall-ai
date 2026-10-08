package com.example.agent.rag.split;

/** 切片输入。{@code content} 为文档原文（可含 front-matter，切片前会剥离）。 */
public record SplitRequest(String content, String docNo, int docVersion, String docTitle, ChunkParams params) {
}
