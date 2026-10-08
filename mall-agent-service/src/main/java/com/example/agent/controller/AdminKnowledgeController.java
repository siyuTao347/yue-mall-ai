package com.example.agent.controller;

import api.common.PageQuery;
import api.common.PageResult;
import api.context.UserContext;
import api.response.ApiResponse;
import com.example.agent.dto.KnowledgeChunkNodeDTO;
import com.example.agent.dto.KnowledgeDocumentContentDTO;
import com.example.agent.dto.KnowledgeDocumentDTO;
import com.example.agent.dto.KnowledgeDocumentSaveRequest;
import com.example.agent.dto.KnowledgeIndexResultDTO;
import com.example.agent.dto.KnowledgeIngestTaskDTO;
import com.example.agent.dto.KnowledgeOverviewDTO;
import com.example.agent.dto.KnowledgeSplitPreviewDTO;
import com.example.agent.dto.VectorRetrievalTestRequest;
import com.example.agent.dto.VectorRetrievalTestResultDTO;
import com.example.agent.entity.KnowledgeDocument;
import com.example.agent.entity.KnowledgeIngestTask;
import com.example.agent.exception.RagApiException;
import com.example.agent.rag.RagStatus;
import com.example.agent.service.KnowledgeDocumentService;
import com.example.agent.service.KnowledgeIngestService;
import com.example.agent.service.KnowledgeStatsService;
import com.example.agent.web.ErrorCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 RAG 知识库接口：文档管理、父子分片、向量入库、任务与检索测试。
 * <p>统一前缀 {@code /api/admin/agent/knowledge}，网关 admin-paths 与拦截器双重校验 ADMIN。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent/knowledge")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeDocumentService documentService;
    private final KnowledgeIngestService ingestService;
    private final KnowledgeStatsService statsService;

    @Value("${pagination.default-page-size:20}")
    private int defaultPageSize;
    @Value("${pagination.max-page-size:100}")
    private int maxPageSize;

    // ------------------------------------------------------------------ 文档管理

    @GetMapping("/documents")
    public ApiResponse<PageResult<KnowledgeDocumentDTO>> documents(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String indexStatus,
            @RequestParam(required = false) String keyword) {
        PageQuery query = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
        return ApiResponse.success(documentService.page(query.page(), query.pageSize(),
                docType, status, indexStatus, keyword));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocumentDTO> document(@PathVariable Long id) {
        return ApiResponse.success(documentService.detail(id));
    }

    /** 文档正文：编辑抽屉回填使用。 */
    @GetMapping("/documents/{id}/content")
    public ApiResponse<KnowledgeDocumentContentDTO> documentContent(@PathVariable Long id) {
        return ApiResponse.success(documentService.content(id));
    }

    @GetMapping("/documents/{docNo}/versions")
    public ApiResponse<List<KnowledgeDocumentDTO>> versions(@PathVariable String docNo) {
        return ApiResponse.success(documentService.versions(docNo));
    }

    /** 新增文档：内容未变化时幂等返回，否则生成新版本并切片。 */
    @PostMapping("/documents")
    public ApiResponse<KnowledgeIndexResultDTO> create(@Valid @RequestBody KnowledgeDocumentSaveRequest request) {
        KnowledgeDocument document = documentService.saveVersion(request, operatorId());
        KnowledgeIngestTask task = maybeIndex(document, request, true, null);
        return ApiResponse.success(buildResult(document, task));
    }

    /** 修改文档：docNo 不可变，内容变化即生成新版本。 */
    @PutMapping("/documents/{id}")
    public ApiResponse<KnowledgeIndexResultDTO> update(@PathVariable Long id,
                                                       @Valid @RequestBody KnowledgeDocumentSaveRequest request) {
        KnowledgeDocument existing = documentService.require(id);
        if (!existing.getDocNo().equals(request.docNo())) {
            throw RagApiException.badRequest(ErrorCodes.RAG_DOC_DUPLICATED, "docNo 不允许修改");
        }
        KnowledgeDocument document = documentService.saveVersion(request, operatorId());
        KnowledgeIngestTask task = maybeIndex(document, request, false, existing);
        return ApiResponse.success(buildResult(document, task));
    }

    @PostMapping("/documents/{id}/enable")
    public ApiResponse<KnowledgeDocumentDTO> enable(@PathVariable Long id) {
        return ApiResponse.success(documentService.toDTO(documentService.enable(id, operatorId())));
    }

    @PostMapping("/documents/{id}/disable")
    public ApiResponse<KnowledgeDocumentDTO> disable(@PathVariable Long id) {
        return ApiResponse.success(documentService.toDTO(documentService.disable(id, operatorId())));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(id, operatorId());
        return ApiResponse.success(null);
    }

    // ------------------------------------------------------------------ 分片

    /** 切片预览：不落库，只校验标题结构与参数效果。 */
    @PostMapping("/documents/{id}/split")
    public ApiResponse<KnowledgeSplitPreviewDTO> previewSplit(@PathVariable Long id) {
        return ApiResponse.success(documentService.previewSplit(id));
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<KnowledgeChunkNodeDTO>> chunks(@PathVariable Long id) {
        return ApiResponse.success(documentService.chunkTree(id));
    }

    // ------------------------------------------------------------------ 向量入库

    /** 执行切片结果的向量化入库；reembed=true 时忽略既有状态全量重算。 */
    @PostMapping("/documents/{id}/index")
    public ApiResponse<KnowledgeIndexResultDTO> index(
            @PathVariable Long id,
            @RequestParam(name = "reembed", required = false, defaultValue = "false") boolean reembed) {
        KnowledgeDocument document = documentService.require(id);
        KnowledgeIngestTask task = ingestService.submit(id, operatorId(), reembed, true);
        return ApiResponse.success(buildResult(document, task));
    }

    /** 重新向量化：只刷新向量，不改变文档启用状态。 */
    @PostMapping("/documents/{id}/re-embed")
    public ApiResponse<KnowledgeIndexResultDTO> reEmbed(@PathVariable Long id) {
        KnowledgeDocument document = documentService.require(id);
        KnowledgeIngestTask task = ingestService.submit(id, operatorId(), true, false);
        return ApiResponse.success(buildResult(document, task));
    }

    @PostMapping("/chunks/{chunkId}/re-embed")
    public ApiResponse<Boolean> reEmbedChunk(@PathVariable Long chunkId) {
        return ApiResponse.success(ingestService.reEmbedChunk(chunkId));
    }

    // ------------------------------------------------------------------ 任务

    @GetMapping("/tasks")
    public ApiResponse<PageResult<KnowledgeIngestTaskDTO>> tasks(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String docNo) {
        PageQuery query = PageQuery.of(page, pageSize, defaultPageSize, maxPageSize);
        return ApiResponse.success(ingestService.page(query.page(), query.pageSize(), status, docNo));
    }

    @GetMapping("/tasks/{taskNo}")
    public ApiResponse<KnowledgeIngestTaskDTO> task(@PathVariable String taskNo) {
        return ApiResponse.success(ingestService.detail(taskNo));
    }

    @PostMapping("/tasks/{taskNo}/retry")
    public ApiResponse<KnowledgeIngestTaskDTO> retryTask(@PathVariable String taskNo) {
        return ApiResponse.success(ingestService.retry(taskNo, operatorId()));
    }

    // ------------------------------------------------------------------ 统计与检索测试

    @GetMapping("/stats/overview")
    public ApiResponse<KnowledgeOverviewDTO> overview() {
        return ApiResponse.success(statsService.overview());
    }

    @PostMapping("/retrieval-test")
    public ApiResponse<VectorRetrievalTestResultDTO> retrievalTest(
            @Valid @RequestBody VectorRetrievalTestRequest request) {
        return ApiResponse.success(statsService.retrievalTest(request));
    }

    // ------------------------------------------------------------------ 内部方法

    /**
     * 保存后按需触发向量入库。
     * <p>新增流程只要请求启用即视为启用；更新流程沿用原文档的启用状态，
     * 避免「编辑已启用文档」后新版本长期停留在 DISABLED、旧版本继续对外服务。</p>
     */
    private KnowledgeIngestTask maybeIndex(KnowledgeDocument document, KnowledgeDocumentSaveRequest request,
                                           boolean createFlow, KnowledgeDocument previous) {
        boolean autoIndex = Boolean.TRUE.equals(request.autoIndex());
        if (!autoIndex) {
            return null;
        }
        if (RagStatus.isIndexed(document.getIndexStatus())) {
            // 内容未变化，已有可用向量，无需重复入库
            return null;
        }
        boolean requestedEnabled = !StringUtils.hasText(request.status())
                || RagStatus.DOC_ENABLED.equalsIgnoreCase(request.status().trim());
        boolean wasEnabled = previous != null && RagStatus.DOC_ENABLED.equals(previous.getStatus());
        boolean enableOnSuccess = requestedEnabled && (createFlow || wasEnabled);
        return ingestService.submit(document.getId(), operatorId(), false, enableOnSuccess);
    }

    private KnowledgeIndexResultDTO buildResult(KnowledgeDocument document, KnowledgeIngestTask task) {
        return new KnowledgeIndexResultDTO(document.getId(), document.getDocNo(), document.getVersion(),
                document.getIndexStatus(), task == null ? null : task.getTaskNo(),
                task == null ? null : task.getStatus());
    }

    /** 网关校验通过后 X-User-Id 一定存在；直连场景回落为 0，保证审计字段非空。 */
    private Long operatorId() {
        Long userId = UserContext.getUserId();
        return userId == null ? 0L : userId;
    }
}
