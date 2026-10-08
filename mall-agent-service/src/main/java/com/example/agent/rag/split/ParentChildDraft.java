package com.example.agent.rag.split;

import java.util.List;

/** 一个父片及其子片。 */
public record ParentChildDraft(ChunkDraft parent, List<ChunkDraft> children) {
}
