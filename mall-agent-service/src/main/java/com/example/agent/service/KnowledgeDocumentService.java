package com.example.agent.service;

import api.common.PageResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.agent.config.RagProperties;
import com.example.agent.dto.KnowledgeChunkNodeDTO;
import com.example.agent.dto.KnowledgeDocumentContentDTO;
import com.example.agent.dto.KnowledgeDocumentDTO;
import com.example.agent.dto.KnowledgeDocumentSaveRequest;
import com.example.agent.dto.KnowledgeSplitPreviewDTO;
import com.example.agent.entity.KnowledgeChunk;
import com.example.agent.entity.KnowledgeDocument;
import com.example.agent.exception.RagApiException;
import com.example.agent.mapper.KnowledgeChunkMapper;
import com.example.agent.mapper.KnowledgeDocumentMapper;
import com.example.agent.mapper.KnowledgeIngestTaskMapper;
import com.example.agent.rag.RagStatus;
import com.example.agent.rag.SensitiveContentGuard;
import com.example.agent.rag.split.ChunkDraft;
import com.example.agent.rag.split.ChunkParams;
import com.example.agent.rag.split.ParentChildDraft;
import com.example.agent.rag.split.ParentChildSplitter;
import com.example.agent.rag.split.SplitRequest;
import com.example.agent.rag.split.SplitResult;
import com.example.agent.rag.split.TextUtils;
import com.example.agent.rag.store.VectorStore;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识文档管理：文档 CRUD、版本切换、父子分片落库、分片树与切片预览。
 * <p>MySQL 是权威源；pgvector 只存派生向量，文档变更时按版本清理。</p>
 */
@Slf4j
@Service
public class KnowledgeDocumentService {

    private static final Set<String> DOC_TYPES = Set.of("PRODUCT", "RULE", "POLICY", "AFTER_SALE", "RISK", "FAQ");
    private static final int CONTENT_PREVIEW_CHARS = 400;
    /** 删除文档时向量清理的最大尝试次数与退避间隔（毫秒）。 */
    private static final int VECTOR_CLEANUP_ATTEMPT = 3;
    private static final long[] VECTOR_CLEANUP_BACKOFF_MS = {800L, 2400L};

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIngestTaskMapper taskMapper;
    private final ParentChildSplitter splitter;
    private final SensitiveContentGuard sensitiveContentGuard;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    private final ThreadPoolTaskExecutor ragCleanupExecutor;

    /**
     * 显式构造器注入：Lombok 不会把字段级 {@code @Qualifier} 复制到构造参数，
     * 而容器里存在两个 ThreadPoolTaskExecutor（入库 / 清理），必须显式区分。
     */
    public KnowledgeDocumentService(KnowledgeDocumentMapper documentMapper,
                                    KnowledgeChunkMapper chunkMapper,
                                    KnowledgeIngestTaskMapper taskMapper,
                                    ParentChildSplitter splitter,
                                    SensitiveContentGuard sensitiveContentGuard,
                                    VectorStore vectorStore,
                                    RagProperties ragProperties,
                                    @Qualifier("ragCleanupExecutor") ThreadPoolTaskExecutor ragCleanupExecutor) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.taskMapper = taskMapper;
        this.splitter = splitter;
        this.sensitiveContentGuard = sensitiveContentGuard;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.ragCleanupExecutor = ragCleanupExecutor;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 保存文档：内容未变化时幂等返回当前版本，否则生成新版本并重新切片。
     * <p>POST 与 PUT 共用此逻辑，保证导入脚本重复执行不产生脏数据。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocument saveVersion(KnowledgeDocumentSaveRequest request, Long operatorId) {
        String docNo = request.docNo().trim();
        String docType = normalizeDocType(request.docType());
        String content = TextUtils.normalizeMarkdown(request.content());
        if (content.isBlank()) {
            throw RagApiException.badRequest(ErrorCodes.RAG_DOC_EMPTY, "文档内容为空，无法切片入库");
        }
        // 命中敏感信息时按 rag.admin.sensitive-action 拒绝或脱敏
        content = sensitiveContentGuard.sanitize(content);

        String contentHash = TextUtils.sha256Hex(content);
        KnowledgeDocument latest = documentMapper.selectLatestByDocNo(docNo);
        if (latest != null && contentHash.equals(latest.getContentHash())) {
            log.info("[RAG] 文档内容未变化，幂等返回: docNo={}, version={}", docNo, latest.getVersion());
            return latest;
        }

        int version = latest == null ? 1 : latest.getVersion() + 1;
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocNo(docNo);
        document.setDocType(docType);
        document.setTitle(request.title().trim());
        document.setContent(content);
        document.setContentHash(contentHash);
        document.setVersion(version);
        document.setSplitterVersion(ragProperties.getChunk().getSplitterVersion());
        document.setSourceType(StringUtils.hasText(request.sourceType()) ? request.sourceType() : "MANUAL");
        document.setSourcePath(request.sourcePath());
        document.setCharCount(content.length());
        document.setParentChunkCount(0);
        document.setChildChunkCount(0);
        document.setIndexStatus(RagStatus.INDEX_PENDING);
        document.setVisibility(normalizeVisibility(request.visibility()));
        // 新版本先禁用，向量入库成功后再启用，避免半可用状态参与检索
        document.setStatus(RagStatus.DOC_DISABLED);
        document.setCreatedBy(operatorId);
        document.setUpdatedBy(operatorId);
        documentMapper.insert(document);

        splitAndPersist(document, content, operatorId);
        log.info("[RAG] 文档已保存并切片: docNo={}, version={}, parents={}, children={}",
                docNo, version, document.getParentChunkCount(), document.getChildChunkCount());
        return document;
    }

    /** 执行父子切片并落库：父片先写、回填主键后再写子片。 */
    private void splitAndPersist(KnowledgeDocument document, String content, Long operatorId) {
        SplitResult result = doSplit(document, content);
        if (result.parents().isEmpty()) {
            throw RagApiException.badRequest(ErrorCodes.RAG_SPLIT_FAILED, "切片结果为空，请检查文档标题结构");
        }

        List<KnowledgeChunk> parents = new ArrayList<>(result.parentCount());
        for (ChunkDraft draft : result.parents().stream().map(ParentChildDraft::parent).toList()) {
            parents.add(toChunk(document, draft, null, 0));
        }
        chunkMapper.insertBatch(parents);

        List<KnowledgeChunk> children = new ArrayList<>(result.childCount());
        List<ParentChildDraft> parentDrafts = result.parents();
        for (int i = 0; i < parentDrafts.size(); i++) {
            Long parentId = parents.get(i).getId();
            int childIndex = 0;
            for (ChunkDraft draft : parentDrafts.get(i).children()) {
                childIndex++;
                children.add(toChunk(document, draft, parentId, childIndex));
            }
        }
        if (!children.isEmpty()) {
            chunkMapper.insertBatch(children);
        }

        documentMapper.updateSplitStat(document.getId(), RagStatus.INDEX_SPLIT, parents.size(),
                children.size(), content.length(), operatorId, LocalDateTime.now());
        document.setIndexStatus(RagStatus.INDEX_SPLIT);
        document.setParentChunkCount(parents.size());
        document.setChildChunkCount(children.size());
    }

    private KnowledgeChunk toChunk(KnowledgeDocument document, ChunkDraft draft, Long parentId, int chunkIndex) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(document.getId());
        chunk.setDocNo(document.getDocNo());
        chunk.setDocVersion(document.getVersion());
        chunk.setChunkLevel(draft.level());
        chunk.setParentChunkId(parentId);
        chunk.setChunkNo(draft.chunkNo());
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkHash(draft.chunkHash());
        chunk.setTitlePath(draft.titlePath());
        chunk.setChunkContent(draft.content());
        chunk.setCharCount(draft.charCount());
        chunk.setCharStart(draft.charStart());
        chunk.setCharEnd(draft.charEnd());
        chunk.setKeywordsJson(draft.keywordsJson());
        chunk.setEmbeddingStatus(RagStatus.EMB_PENDING);
        chunk.setSplitForced(draft.splitForced() ? 1 : 0);
        chunk.setTokenCount(draft.tokenCount());
        chunk.setStatus(RagStatus.CHUNK_ENABLED);
        return chunk;
    }

    // ------------------------------------------------------------------ 状态流转

    /** 启用文档：必须已完成向量入库，并自动禁用同 docNo 的其他版本。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocument enable(Long id, Long operatorId) {
        KnowledgeDocument document = require(id);
        if (!RagStatus.isIndexed(document.getIndexStatus())) {
            throw RagApiException.conflict(ErrorCodes.RAG_DOC_INDEXING,
                    "文档尚未完成向量入库（indexStatus=" + document.getIndexStatus() + "），不能启用");
        }
        LocalDateTime now = LocalDateTime.now();
        documentMapper.disableOtherVersions(document.getDocNo(), document.getVersion(), operatorId, now);
        documentMapper.updateStatus(id, RagStatus.DOC_ENABLED, operatorId, now);
        document.setStatus(RagStatus.DOC_ENABLED);
        return document;
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocument disable(Long id, Long operatorId) {
        KnowledgeDocument document = require(id);
        documentMapper.updateStatus(id, RagStatus.DOC_DISABLED, operatorId, LocalDateTime.now());
        document.setStatus(RagStatus.DOC_DISABLED);
        return document;
    }

    /**
     * 物理删除：该版本的文档正文、父子分片与入库任务在 MySQL 中硬删除，pgvector 中该版本向量同步清理。
     *
     * <p>删除不可恢复；由于不存在外键约束，删除顺序为「取消在跑任务 → 删分片 → 删任务 → 删文档」，
     * 全部在一个事务内完成。向量清理放在事务提交后异步执行：</p>
     * <ol>
     *   <li>pgvector 删除会与入库批次写同一批行而互等行锁，同步清理会让管理端删除请求超过网关 5s
     *       响应超时（504）；</li>
     *   <li>MySQL 行已提交删除，异步线程读到的必然是最终状态。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        KnowledgeDocument document = require(id);
        LocalDateTime now = LocalDateTime.now();
        int canceled = taskMapper.cancelActiveByDocumentId(id, "文档已删除，任务取消", now);
        if (canceled > 0) {
            log.info("[RAG] 文档存在进行中的入库任务，已取消 {} 个: docNo={}", canceled, document.getDocNo());
        }
        int deletedChunks = chunkMapper.deleteByDocumentId(id);
        int deletedTasks = taskMapper.deleteByDocumentId(id);
        documentMapper.deleteById(id);
        log.info("[RAG] 文档已物理删除: docNo={}, version={}, chunks={}, tasks={}, operator={}",
                document.getDocNo(), document.getVersion(), deletedChunks, deletedTasks, operatorId);

        String docNo = document.getDocNo();
        Integer version = document.getVersion();
        submitAfterCommit(() -> ragCleanupExecutor.execute(() -> cleanupVectors(docNo, version)));
    }

    /**
     * 清理向量：删完再校验条数，仍有残留则退避重试。
     *
     * <p>校验是必要的：被取消的入库批次可能已在途写完向量才落库，
     * 若清理先于该批次提交，单次删除会留下孤儿向量并被后续同名版本检索命中。</p>
     */
    private void cleanupVectors(String docNo, Integer version) {
        for (int attempt = 1; attempt <= VECTOR_CLEANUP_ATTEMPT; attempt++) {
            try {
                vectorStore.deleteByDoc(docNo, version);
                long left = vectorStore.countByDoc(docNo, version);
                if (left == 0) {
                    log.info("[RAG] 向量已物理清理: docNo={}, version={}", docNo, version);
                    return;
                }
                log.warn("[RAG] 向量删除后仍有残留（第 {}/{} 次）: docNo={}, version={}, left={}",
                        attempt, VECTOR_CLEANUP_ATTEMPT, docNo, version, left);
            } catch (Exception e) {
                log.error("[RAG] 删除向量失败（第 {}/{} 次）: docNo={}, version={}",
                        attempt, VECTOR_CLEANUP_ATTEMPT, docNo, version, e);
            }
            if (attempt < VECTOR_CLEANUP_ATTEMPT) {
                sleepQuietly(VECTOR_CLEANUP_BACKOFF_MS[Math.min(attempt - 1, VECTOR_CLEANUP_BACKOFF_MS.length - 1)]);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        // MySQL 侧已删除，无法再通过文档记录补偿，只能告警人工对账
        log.error("[RAG] 向量清理未完成，需人工对账: docNo={}, version={}", docNo, version);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 事务提交后回调，保证异步清理读到的是已提交状态。 */
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

    // ------------------------------------------------------------------ 查询

    public PageResult<KnowledgeDocumentDTO> page(int pageNo, int pageSize, String docType, String status,
                                                 String indexStatus, String keyword) {
        Page<KnowledgeDocument> page = new Page<>(pageNo, pageSize);
        Page<KnowledgeDocument> result = documentMapper.selectPage(page,
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(StringUtils.hasText(docType), KnowledgeDocument::getDocType, docType)
                        .eq(StringUtils.hasText(status), KnowledgeDocument::getStatus, status)
                        .eq(StringUtils.hasText(indexStatus), KnowledgeDocument::getIndexStatus, indexStatus)
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(KnowledgeDocument::getDocNo, keyword).or()
                                .like(KnowledgeDocument::getTitle, keyword))
                        .orderByDesc(KnowledgeDocument::getId));
        List<KnowledgeDocumentDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(records, result.getTotal(), pageNo, pageSize);
    }

    public KnowledgeDocumentDTO detail(Long id) {
        return toDTO(require(id));
    }

    /** 文档正文：仅编辑场景使用，列表/详情接口不返回大字段。 */
    public KnowledgeDocumentContentDTO content(Long id) {
        KnowledgeDocument document = require(id);
        return new KnowledgeDocumentContentDTO(document.getId(), document.getDocNo(), document.getDocType(),
                document.getTitle(), document.getVersion(), document.getContent(), document.getCharCount(),
                document.getIndexStatus(), document.getStatus(), document.getVisibility());
    }

    /** 文档版本列表（含已禁用历史版本），用于引用解释与回滚。 */
    public List<KnowledgeDocumentDTO> versions(String docNo) {
        return documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getDocNo, docNo)
                        .orderByDesc(KnowledgeDocument::getVersion))
                .stream().map(this::toDTO).toList();
    }

    /** 分片树：父片携带子片，供管理页面预览切片效果。 */
    public List<KnowledgeChunkNodeDTO> chunkTree(Long documentId) {
        require(documentId);
        List<KnowledgeChunk> parents = chunkMapper.selectByLevel(documentId, KnowledgeChunk.LEVEL_PARENT);
        List<KnowledgeChunk> children = chunkMapper.selectByLevel(documentId, KnowledgeChunk.LEVEL_CHILD);
        Map<Long, List<KnowledgeChunkNodeDTO>> childMap = new LinkedHashMap<>();
        for (KnowledgeChunk child : children) {
            childMap.computeIfAbsent(child.getParentChunkId(), key -> new ArrayList<>())
                    .add(toNode(child, false, List.of()));
        }
        List<KnowledgeChunkNodeDTO> nodes = new ArrayList<>(parents.size());
        for (KnowledgeChunk parent : parents) {
            nodes.add(toNode(parent, false, childMap.getOrDefault(parent.getId(), List.of())));
        }
        return nodes;
    }

    /** 切片预览：不落库，用于调整参数与检查标题结构。 */
    public KnowledgeSplitPreviewDTO previewSplit(Long documentId) {
        KnowledgeDocument document = require(documentId);
        SplitResult result = doSplit(document, document.getContent());
        List<KnowledgeChunkNodeDTO> nodes = new ArrayList<>(result.parents().size());
        for (ParentChildDraft draft : result.parents()) {
            List<KnowledgeChunkNodeDTO> children = new ArrayList<>(draft.children().size());
            for (ChunkDraft child : draft.children()) {
                children.add(fromDraft(child, List.of()));
            }
            nodes.add(fromDraft(draft.parent(), children));
        }
        return new KnowledgeSplitPreviewDTO(document.getDocNo(), document.getVersion(), document.getTitle(),
                result.normalizedContent().length(), result.parentCount(), result.childCount(), nodes);
    }

    // ------------------------------------------------------------------ 内部方法

    private SplitResult doSplit(KnowledgeDocument document, String content) {
        return splitter.split(new SplitRequest(content, document.getDocNo(), document.getVersion(),
                document.getTitle(), chunkParams()));
    }

    public ChunkParams chunkParams() {
        RagProperties.Chunk chunk = ragProperties.getChunk();
        return new ChunkParams(chunk.getParent().getTargetChars(), chunk.getParent().getMaxChars(),
                chunk.getParent().getMinChars(), chunk.getChild().getTargetChars(),
                chunk.getChild().getMaxChars(), chunk.getChild().getMinChars(),
                chunk.getChild().getOverlapChars(), chunk.getChild().getMaxPerParent(),
                chunk.getKeywordLimit());
    }

    public KnowledgeDocument require(Long id) {
        KnowledgeDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw RagApiException.notFound(ErrorCodes.RAG_DOC_NOT_FOUND, "知识文档不存在: " + id);
        }
        return document;
    }

    private String normalizeDocType(String docType) {
        String normalized = docType == null ? "" : docType.trim().toUpperCase();
        if (!DOC_TYPES.contains(normalized)) {
            throw RagApiException.badRequest(ErrorCodes.RAG_DOC_TYPE_INVALID,
                    "docType 非法，允许值: " + DOC_TYPES);
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        if (RagStatus.VISIBILITY_INTERNAL.equalsIgnoreCase(visibility)) {
            return RagStatus.VISIBILITY_INTERNAL;
        }
        return RagStatus.VISIBILITY_PUBLIC;
    }

    public KnowledgeDocumentDTO toDTO(KnowledgeDocument document) {
        return new KnowledgeDocumentDTO(document.getId(), document.getDocNo(), document.getDocType(),
                document.getTitle(), document.getVersion(), document.getSplitterVersion(),
                document.getSourceType(), document.getSourcePath(), document.getCharCount(),
                document.getParentChunkCount(), document.getChildChunkCount(), document.getIndexStatus(),
                document.getVisibility(), document.getStatus(), document.getCreatedTime(),
                document.getUpdatedTime());
    }

    private KnowledgeChunkNodeDTO toNode(KnowledgeChunk chunk, boolean withContent,
                                         List<KnowledgeChunkNodeDTO> children) {
        String content = chunk.getChunkContent();
        if (!withContent && content != null && content.length() > CONTENT_PREVIEW_CHARS) {
            content = content.substring(0, CONTENT_PREVIEW_CHARS) + "…";
        }
        return new KnowledgeChunkNodeDTO(chunk.getId(), chunk.getChunkNo(), chunk.getChunkLevel(),
                chunk.getTitlePath(), chunk.getCharCount(), chunk.getCharStart(), chunk.getCharEnd(),
                chunk.getEmbeddingStatus(), chunk.getEmbeddingModel(), chunk.getEmbeddingDim(),
                chunk.getSplitForced() != null && chunk.getSplitForced() == 1, content, children);
    }

    private KnowledgeChunkNodeDTO fromDraft(ChunkDraft draft, List<KnowledgeChunkNodeDTO> children) {
        String content = draft.content();
        if (content.length() > CONTENT_PREVIEW_CHARS) {
            content = content.substring(0, CONTENT_PREVIEW_CHARS) + "…";
        }
        return new KnowledgeChunkNodeDTO(null, draft.chunkNo(), draft.level(), draft.titlePath(),
                draft.charCount(), draft.charStart(), draft.charEnd(), RagStatus.EMB_PENDING, null, null,
                draft.splitForced(), content, children);
    }
}
