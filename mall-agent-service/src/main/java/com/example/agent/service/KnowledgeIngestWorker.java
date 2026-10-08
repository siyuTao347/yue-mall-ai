package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.entity.KnowledgeChunk;
import com.example.agent.entity.KnowledgeDocument;
import com.example.agent.entity.KnowledgeIngestTask;
import com.example.agent.mapper.KnowledgeChunkMapper;
import com.example.agent.mapper.KnowledgeDocumentMapper;
import com.example.agent.mapper.KnowledgeIngestTaskMapper;
import com.example.agent.exception.RagApiException;
import com.example.agent.web.ErrorCodes;
import com.example.agent.rag.RagStatus;
import com.example.agent.rag.embed.EmbeddingClient;
import com.example.agent.rag.embed.EmbeddingTextBuilder;
import com.example.agent.rag.split.TextUtils;
import com.example.agent.rag.store.ChunkVector;
import com.example.agent.rag.store.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量化入库执行器：按批次调用 Embedding 并写入 pgvector。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>不做大事务</b>：每个批次独立提交，中途失败可从 PENDING/FAILED 的分片续跑；</li>
 *   <li><b>批次级重试</b>：模型抖动时按退避重试，失败批次标记 FAILED 不阻塞其他批次；</li>
 *   <li><b>MySQL 权威</b>：向量写入成功后才回写 embedding_status=READY。</li>
 * </ol>
 */
@Slf4j
@Component
public class KnowledgeIngestWorker {

    private static final int EMBED_MAX_ATTEMPT = 3;
    private static final long[] RETRY_BACKOFF_MS = {500L, 2000L};

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIngestTaskMapper taskMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public KnowledgeIngestWorker(KnowledgeDocumentMapper documentMapper,
                                 KnowledgeChunkMapper chunkMapper,
                                 KnowledgeIngestTaskMapper taskMapper,
                                 EmbeddingClient embeddingClient,
                                 VectorStore vectorStore,
                                 RagProperties ragProperties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.taskMapper = taskMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    public void execute(Long taskId, boolean reembedAll, boolean enableOnSuccess) {
        KnowledgeIngestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[RAG] 向量化任务不存在，跳过: taskId={}", taskId);
            return;
        }
        KnowledgeDocument document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            finish(task, RagStatus.TASK_FAILED, "文档不存在或已被删除");
            return;
        }
        if (!vectorStore.ping()) {
            finish(task, RagStatus.TASK_FAILED, "pgvector 不可用，任务终止（可重试）");
            return;
        }

        if (RagStatus.TASK_CANCELED.equals(task.getStatus())) {
            log.info("[RAG] 任务已取消，跳过执行: taskNo={}", task.getTaskNo());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        task.setStatus(RagStatus.TASK_RUNNING);
        task.setStartedTime(now);
        taskMapper.updateById(task);
        documentMapper.updateIndexStatus(document.getId(), RagStatus.INDEX_INDEXING, now);

        List<KnowledgeChunk> children = reembedAll
                ? chunkMapper.selectAllChildren(document.getId())
                : chunkMapper.selectEmbeddableChildren(document.getId());
        task.setTotalChunks(children.size());
        taskMapper.updateById(task);

        int batchSize = Math.max(1, ragProperties.getEmbedding().getBatchSize());
        int handled = 0;
        int failed = 0;
        for (int i = 0; i < children.size(); i += batchSize) {
            if (isCanceled(taskId)) {
                return;
            }
            List<KnowledgeChunk> batch = children.subList(i, Math.min(i + batchSize, children.size()));
            if (embedBatch(document, batch)) {
                handled += batch.size();
            } else {
                handled += batch.size();
                failed += batch.size();
            }
            task.setProcessedChunks(handled);
            task.setFailedChunks(failed);
            taskMapper.updateById(task);
        }

        int ready = chunkMapper.countChildrenByEmbeddingStatus(document.getId(), RagStatus.EMB_READY);
        int pending = chunkMapper.countChildrenByEmbeddingStatus(document.getId(), RagStatus.EMB_PENDING);
        int embedFailed = chunkMapper.countChildrenByEmbeddingStatus(document.getId(), RagStatus.EMB_FAILED);
        // 批次执行期间文档可能已被物理删除：此时不能再回写索引状态、更不能启用文档或停用同编号其他版本
        if (documentMapper.selectById(document.getId()) == null) {
            log.info("[RAG] 文档已删除，跳过收尾状态回写: docNo={}, version={}",
                    document.getDocNo(), document.getVersion());
            return;
        }
        String indexStatus = resolveIndexStatus(ready, pending, embedFailed);
        LocalDateTime finished = LocalDateTime.now();
        documentMapper.updateIndexStatus(document.getId(), indexStatus, finished);

        if (enableOnSuccess && RagStatus.isIndexed(indexStatus)) {
            documentMapper.disableOtherVersions(document.getDocNo(), document.getVersion(),
                    task.getOperatorId(), finished);
            documentMapper.updateStatus(document.getId(), RagStatus.DOC_ENABLED, task.getOperatorId(), finished);
        }
        String taskStatus = switch (indexStatus) {
            case RagStatus.INDEX_READY -> RagStatus.TASK_SUCCESS;
            case RagStatus.INDEX_PARTIAL -> RagStatus.TASK_PARTIAL;
            default -> RagStatus.TASK_FAILED;
        };
        finish(task, taskStatus, embedFailed > 0 ? (embedFailed + " 个子片向量化失败，可重试") : null);
        log.info("[RAG] 向量化任务结束: taskNo={}, docNo={}, indexStatus={}, ready={}, failed={}",
                task.getTaskNo(), document.getDocNo(), indexStatus, ready, embedFailed);
    }

    /** 任务被取消（文档已删除等）时停止剩余批次，避免写出孤儿向量。 */
    private boolean isCanceled(Long taskId) {
        KnowledgeIngestTask latest = taskMapper.selectById(taskId);
        if (latest == null) {
            // 任务行已随文档物理删除：等价于取消，必须立即停止，否则会为已删除文档继续写孤儿向量
            log.info("[RAG] 任务已随文档删除，停止向量化: taskId={}", taskId);
            return true;
        }
        if (RagStatus.TASK_CANCELED.equals(latest.getStatus())) {
            log.info("[RAG] 任务已取消，停止向量化: taskNo={}", latest.getTaskNo());
            return true;
        }
        return false;
    }

    /** 单个子片重新向量化：用于修复失败分片，不影响其他分片。 */
    public boolean reEmbedChunk(Long chunkId) {
        KnowledgeChunk chunk = chunkMapper.selectChunkById(chunkId);
        if (chunk == null) {
            throw RagApiException.notFound(ErrorCodes.RAG_CHUNK_NOT_FOUND, "分片不存在: " + chunkId);
        }
        if (chunk.getChunkLevel() == null || chunk.getChunkLevel() != KnowledgeChunk.LEVEL_CHILD) {
            throw RagApiException.badRequest(ErrorCodes.RAG_CHUNK_NOT_FOUND, "只有子片支持单独重新向量化");
        }
        KnowledgeDocument document = documentMapper.selectById(chunk.getDocumentId());
        if (document == null) {
            throw RagApiException.notFound(ErrorCodes.RAG_DOC_NOT_FOUND, "分片所属文档不存在");
        }
        if (!vectorStore.ping()) {
            throw RagApiException.unavailable(ErrorCodes.RAG_VECTOR_STORE_UNAVAILABLE,
                    "pgvector 不可用，无法写入向量");
        }
        return embedBatch(document, List.of(chunk));
    }

    /** 单批次向量化 + 入库；失败标记该批次 FAILED，不影响其他批次。 */
    private boolean embedBatch(KnowledgeDocument document, List<KnowledgeChunk> batch) {
        int maxTextChars = ragProperties.getEmbedding().getMaxTextChars();
        List<String> texts = new ArrayList<>(batch.size());
        for (KnowledgeChunk chunk : batch) {
            texts.add(EmbeddingTextBuilder.build(document.getDocType(), chunk.getTitlePath(),
                    chunk.getChunkContent(), maxTextChars));
        }

        List<float[]> vectors = null;
        for (int attempt = 1; attempt <= EMBED_MAX_ATTEMPT && vectors == null; attempt++) {
            try {
                vectors = embeddingClient.embed(texts);
            } catch (Exception e) {
                log.warn("[RAG] Embedding 调用失败（第 {}/{} 次）: {}", attempt, EMBED_MAX_ATTEMPT, e.getMessage());
                if (attempt < EMBED_MAX_ATTEMPT) {
                    sleep(RETRY_BACKOFF_MS[Math.min(attempt - 1, RETRY_BACKOFF_MS.length - 1)]);
                }
            }
        }
        if (vectors == null || vectors.size() != batch.size()) {
            markFailed(batch);
            return false;
        }

        try {
            List<ChunkVector> chunkVectors = new ArrayList<>(batch.size());
            List<String> inputHashes = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                KnowledgeChunk chunk = batch.get(i);
                String inputHash = TextUtils.sha256Hex(embeddingClient.model() + "|" + texts.get(i));
                inputHashes.add(inputHash);
                chunkVectors.add(new ChunkVector(chunk.getId(), document.getId(), document.getDocNo(),
                        document.getVersion(), document.getDocType(), chunk.getChunkNo(),
                        vectors.get(i), embeddingClient.model(), vectors.get(i).length, inputHash,
                        RagStatus.EMB_READY));
            }
            vectorStore.upsertAll(chunkVectors);
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < batch.size(); i++) {
                chunkMapper.markEmbeddingReady(batch.get(i).getId(), embeddingClient.model(),
                        vectors.get(i).length, inputHashes.get(i), now);
            }
            return true;
        } catch (Exception e) {
            log.error("[RAG] 向量写入 pgvector 失败: size={}", batch.size(), e);
            markFailed(batch);
            return false;
        }
    }

    private void markFailed(List<KnowledgeChunk> batch) {
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeChunk chunk : batch) {
            chunkMapper.markEmbeddingStatus(chunk.getId(), RagStatus.EMB_FAILED, now);
        }
    }

    private String resolveIndexStatus(int ready, int pending, int failed) {
        if (pending == 0 && failed == 0) {
            return RagStatus.INDEX_READY;
        }
        return ready > 0 ? RagStatus.INDEX_PARTIAL : RagStatus.INDEX_FAILED;
    }

    private void finish(KnowledgeIngestTask task, String status, String error) {
        task.setStatus(status);
        task.setLastError(error);
        task.setFinishedTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
