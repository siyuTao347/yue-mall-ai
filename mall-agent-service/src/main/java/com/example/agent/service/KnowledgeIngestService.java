package com.example.agent.service;

import api.common.PageResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.agent.config.RagProperties;
import com.example.agent.dto.KnowledgeIngestTaskDTO;
import com.example.agent.entity.KnowledgeDocument;
import com.example.agent.entity.KnowledgeIngestTask;
import com.example.agent.exception.RagApiException;
import com.example.agent.mapper.KnowledgeIngestTaskMapper;
import com.example.agent.rag.RagStatus;
import com.example.agent.rag.embed.EmbeddingClient;
import com.example.agent.web.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量化任务编排：创建任务、提交线程池、查询与重试。
 * <p>真正的入库逻辑在 {@link KnowledgeIngestWorker} 中，按批次提交、可断点续跑。</p>
 */
@Slf4j
@Service
public class KnowledgeIngestService {

    private static final DateTimeFormatter TASK_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final KnowledgeIngestTaskMapper taskMapper;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeIngestWorker worker;
    private final EmbeddingClient embeddingClient;
    private final RagProperties ragProperties;
    private final ThreadPoolTaskExecutor ragIngestExecutor;

    public KnowledgeIngestService(KnowledgeIngestTaskMapper taskMapper,
                                  KnowledgeDocumentService documentService,
                                  KnowledgeIngestWorker worker,
                                  EmbeddingClient embeddingClient,
                                  RagProperties ragProperties,
                                  @Qualifier("ragIngestExecutor") ThreadPoolTaskExecutor ragIngestExecutor) {
        this.taskMapper = taskMapper;
        this.documentService = documentService;
        this.worker = worker;
        this.embeddingClient = embeddingClient;
        this.ragProperties = ragProperties;
        this.ragIngestExecutor = ragIngestExecutor;
    }

    /**
     * 提交向量化任务。同一文档已有进行中任务时直接返回该任务（幂等）。
     *
     * @param reembedAll      是否忽略既有状态全量重算
     * @param enableOnSuccess  成功后是否自动启用该版本
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeIngestTask submit(Long documentId, Long operatorId, boolean reembedAll,
                                      boolean enableOnSuccess) {
        assertDimensionConsistent();
        KnowledgeDocument document = documentService.require(documentId);
        KnowledgeIngestTask active = taskMapper.selectActiveByDocumentId(documentId);
        if (active != null) {
            log.info("[RAG] 文档已有进行中的向量化任务，幂等返回: docNo={}, taskNo={}",
                    document.getDocNo(), active.getTaskNo());
            return active;
        }
        if (document.getChildChunkCount() == null || document.getChildChunkCount() == 0) {
            throw RagApiException.badRequest(ErrorCodes.RAG_SPLIT_FAILED, "文档没有可向量化的子片，请先切片");
        }

        KnowledgeIngestTask task = new KnowledgeIngestTask();
        task.setTaskNo(nextTaskNo());
        task.setDocumentId(documentId);
        task.setDocNo(document.getDocNo());
        task.setDocVersion(document.getVersion());
        task.setTaskType(reembedAll ? RagStatus.TASK_TYPE_REBUILD : RagStatus.TASK_TYPE_EMBED);
        task.setStatus(RagStatus.TASK_PENDING);
        task.setTotalChunks(0);
        task.setProcessedChunks(0);
        task.setFailedChunks(0);
        task.setRetryCount(0);
        task.setOperatorId(operatorId);
        taskMapper.insert(task);

        Long taskId = task.getId();
        String taskNo = task.getTaskNo();
        // 必须在事务提交后再派发，否则工作线程读不到刚插入的任务行
        submitAfterCommit(() -> ragIngestExecutor.execute(() -> {
            try {
                worker.execute(taskId, reembedAll, enableOnSuccess);
            } catch (Exception e) {
                log.error("[RAG] 向量化任务执行异常: taskNo={}", taskNo, e);
            }
        }));
        return task;
    }

    public PageResult<KnowledgeIngestTaskDTO> page(int pageNo, int pageSize, String status, String docNo) {
        Page<KnowledgeIngestTask> page = new Page<>(pageNo, pageSize);
        Page<KnowledgeIngestTask> result = taskMapper.selectPage(page,
                Wrappers.<KnowledgeIngestTask>lambdaQuery()
                        .eq(StringUtils.hasText(status), KnowledgeIngestTask::getStatus, status)
                        .eq(StringUtils.hasText(docNo), KnowledgeIngestTask::getDocNo, docNo)
                        .orderByDesc(KnowledgeIngestTask::getId));
        return PageResult.of(result.getRecords().stream().map(this::toDTO).toList(), result.getTotal(),
                pageNo, pageSize);
    }

    public KnowledgeIngestTaskDTO detail(String taskNo) {
        return toDTO(requireByTaskNo(taskNo));
    }

    /** 重试失败任务：生成一条新任务重新执行，保留历史记录。 */
    public KnowledgeIngestTaskDTO retry(String taskNo, Long operatorId) {
        KnowledgeIngestTask previous = requireByTaskNo(taskNo);
        if (!RagStatus.TASK_FAILED.equals(previous.getStatus())
                && !RagStatus.TASK_PARTIAL.equals(previous.getStatus())) {
            throw RagApiException.conflict(ErrorCodes.RAG_TASK_CONFLICT,
                    "只有 FAILED 或 PARTIAL 的任务可以重试，当前状态=" + previous.getStatus());
        }
        KnowledgeIngestTask task = submit(previous.getDocumentId(), operatorId,
                RagStatus.TASK_TYPE_REBUILD.equals(previous.getTaskType()), true);
        return toDTO(task);
    }

    /** 单个子片重新向量化，返回是否成功。 */
    public boolean reEmbedChunk(Long chunkId) {
        assertDimensionConsistent();
        return worker.reEmbedChunk(chunkId);
    }

    public KnowledgeIngestTask requireByTaskNo(String taskNo) {
        KnowledgeIngestTask task = taskMapper.selectByTaskNo(taskNo);
        if (task == null) {
            throw RagApiException.notFound(ErrorCodes.RAG_TASK_NOT_FOUND, "向量化任务不存在: " + taskNo);
        }
        return task;
    }

    /** 维度必须与配置、表结构三方一致，否则写入必然失败，提前拦截。 */
    private void assertDimensionConsistent() {
        int clientDim = embeddingClient.dim();
        int configuredDim = ragProperties.getPgvector().getEmbeddingDim();
        if (clientDim != configuredDim) {
            throw RagApiException.unavailable(ErrorCodes.RAG_VECTOR_DIM_MISMATCH,
                    "向量维度不一致：embedding.dim=%d, pgvector.embedding-dim=%d".formatted(clientDim, configuredDim));
        }
        if (clientDim != ragProperties.getEmbedding().getDim()) {
            throw RagApiException.unavailable(ErrorCodes.RAG_VECTOR_DIM_MISMATCH,
                    "向量维度不一致：embedding.dim=%d, rag.embedding.dim=%d"
                            .formatted(clientDim, ragProperties.getEmbedding().getDim()));
        }
    }

    /** 事务提交后回调，避免异步线程早于提交读到任务行。 */
    private void submitAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String nextTaskNo() {
        return "INGEST-%s-%04d".formatted(LocalDateTime.now().format(TASK_NO_FORMAT),
                SEQUENCE.incrementAndGet() % 10000);
    }

    public KnowledgeIngestTaskDTO toDTO(KnowledgeIngestTask task) {
        return new KnowledgeIngestTaskDTO(task.getId(), task.getTaskNo(), task.getDocumentId(),
                task.getDocNo(), task.getDocVersion(), task.getTaskType(), task.getStatus(),
                task.getTotalChunks(), task.getProcessedChunks(), task.getFailedChunks(),
                task.getRetryCount(), task.getLastError(), task.getStartedTime(), task.getFinishedTime(),
                task.getCreatedTime());
    }
}
