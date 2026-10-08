package com.example.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agent.config.RagProperties;
import com.example.agent.dto.KnowledgeChunkNodeDTO;
import com.example.agent.dto.KnowledgeOverviewDTO;
import com.example.agent.dto.VectorRetrievalTestRequest;
import com.example.agent.dto.VectorRetrievalTestResultDTO;
import com.example.agent.entity.KnowledgeChunk;
import com.example.agent.entity.KnowledgeDocument;
import com.example.agent.exception.RagApiException;
import com.example.agent.mapper.KnowledgeChunkMapper;
import com.example.agent.mapper.KnowledgeDocumentMapper;
import com.example.agent.rag.RagStatus;
import com.example.agent.rag.embed.EmbeddingClient;
import com.example.agent.rag.store.RagHealthState;
import com.example.agent.rag.store.VectorHit;
import com.example.agent.rag.store.VectorSearchFilter;
import com.example.agent.rag.store.VectorStore;
import com.example.agent.web.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 知识库统计与检索测试（用于验证向量入库效果）。 */
@Slf4j
@Service
public class KnowledgeStatsService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RagHealthState healthState;
    private final RagProperties ragProperties;

    public KnowledgeStatsService(KnowledgeDocumentMapper documentMapper, KnowledgeChunkMapper chunkMapper,
                                 EmbeddingClient embeddingClient, VectorStore vectorStore,
                                 RagHealthState healthState, RagProperties ragProperties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.healthState = healthState;
        this.ragProperties = ragProperties;
    }

    public KnowledgeOverviewDTO overview() {
        long documentTotal = documentMapper.selectCount(null);
        long documentEnabled = documentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getStatus, RagStatus.DOC_ENABLED));
        long parentTotal = chunkMapper.selectCount(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getChunkLevel, KnowledgeChunk.LEVEL_PARENT));
        long childTotal = chunkMapper.selectCount(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getChunkLevel, KnowledgeChunk.LEVEL_CHILD));
        long embeddedTotal = chunkMapper.selectCount(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getChunkLevel, KnowledgeChunk.LEVEL_CHILD)
                .eq(KnowledgeChunk::getEmbeddingStatus, RagStatus.EMB_READY));
        long embeddedFailed = chunkMapper.selectCount(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getChunkLevel, KnowledgeChunk.LEVEL_CHILD)
                .eq(KnowledgeChunk::getEmbeddingStatus, RagStatus.EMB_FAILED));

        long vectorCount = 0;
        try {
            vectorCount = vectorStore.count();
        } catch (Exception e) {
            log.warn("[RAG] 统计向量条数失败: {}", e.getMessage());
        }
        return new KnowledgeOverviewDTO(documentTotal, documentEnabled, parentTotal, childTotal,
                embeddedTotal, embeddedFailed, vectorCount, healthState.isVectorAvailable(),
                healthState.getVectorVersion(), indexStatusDistribution());
    }

    private Map<String, Long> indexStatusDistribution() {
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        wrapper.select("index_status", "COUNT(*) AS cnt").groupBy("index_status");
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (Map<String, Object> row : documentMapper.selectMaps(wrapper)) {
            Object status = row.get("index_status");
            Object count = row.get("cnt");
            if (status != null && count instanceof Number number) {
                distribution.put(status.toString(), number.longValue());
            }
        }
        return distribution;
    }

    /**
     * 检索测试：向量召回子片 → 回溯父片 → 组装上下文。
     * <p>本增量只支持 {@code VECTOR}；KEYWORD / HYBRID 在检索增量中实现。</p>
     */
    public VectorRetrievalTestResultDTO retrievalTest(VectorRetrievalTestRequest request) {
        String requestedMode = request.mode() == null ? "VECTOR" : request.mode().toUpperCase();
        if (!"VECTOR".equals(requestedMode)) {
            throw RagApiException.badRequest(ErrorCodes.RAG_RETRIEVAL_MODE_UNSUPPORTED,
                    "本版本仅支持 mode=VECTOR（关键词与混合检索为后续增量）");
        }
        if (!healthState.isVectorAvailable()) {
            throw RagApiException.unavailable(ErrorCodes.RAG_VECTOR_STORE_UNAVAILABLE,
                    "pgvector 不可用，请检查向量库连接与 vector 扩展");
        }

        long start = System.currentTimeMillis();
        int childTopK = request.topK() == null ? ragProperties.getRetrieval().getChildTopK() : request.topK();
        int parentTopN = request.parentTopN() == null ? ragProperties.getRetrieval().getParentTopN()
                : request.parentTopN();

        float[] queryVector = embeddingClient.embed(List.of(request.query())).get(0);
        List<VectorHit> hits = vectorStore.search(queryVector,
                VectorSearchFilter.of(request.docTypes(), embeddingClient.model(), embeddingClient.dim()),
                childTopK);

        Map<Long, KnowledgeChunk> childById = new LinkedHashMap<>();
        if (!hits.isEmpty()) {
            for (KnowledgeChunk chunk : chunkMapper.selectByIds(hits.stream().map(VectorHit::chunkId).toList())) {
                childById.put(chunk.getId(), chunk);
            }
        }

        List<VectorRetrievalTestResultDTO.ChildHit> childHits = new ArrayList<>(hits.size());
        Map<Long, Double> parentScore = new LinkedHashMap<>();
        for (VectorHit hit : hits) {
            KnowledgeChunk child = childById.get(hit.chunkId());
            String titlePath = child == null ? null : child.getTitlePath();
            String content = child == null ? null : child.getChunkContent();
            childHits.add(new VectorRetrievalTestResultDTO.ChildHit(hit.chunkNo(), hit.chunkId(), hit.docNo(),
                    hit.docVersion(), hit.docType(), titlePath, hit.score(), content));
            if (child != null && child.getParentChunkId() != null) {
                parentScore.merge(child.getParentChunkId(), hit.score(), Math::max);
            }
        }

        List<KnowledgeChunkNodeDTO> contexts = new ArrayList<>();
        List<Long> parentIds = parentScore.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(parentTopN)
                .map(Map.Entry::getKey)
                .toList();
        if (!parentIds.isEmpty()) {
            Map<Long, KnowledgeChunk> parentById = new LinkedHashMap<>();
            for (KnowledgeChunk parent : chunkMapper.selectByIds(new LinkedHashSet<>(parentIds))) {
                parentById.put(parent.getId(), parent);
            }
            for (Long parentId : parentIds) {
                KnowledgeChunk parent = parentById.get(parentId);
                if (parent == null) {
                    continue;
                }
                contexts.add(new KnowledgeChunkNodeDTO(parent.getId(), parent.getChunkNo(),
                        parent.getChunkLevel(), parent.getTitlePath(), parent.getCharCount(),
                        parent.getCharStart(), parent.getCharEnd(), parent.getEmbeddingStatus(),
                        parent.getEmbeddingModel(), parent.getEmbeddingDim(),
                        parent.getSplitForced() != null && parent.getSplitForced() == 1,
                        parent.getChunkContent(), List.of()));
            }
        }
        return new VectorRetrievalTestResultDTO(requestedMode, "VECTOR",
                System.currentTimeMillis() - start, childTopK, childHits, contexts);
    }
}
