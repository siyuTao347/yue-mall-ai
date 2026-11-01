package com.example.moneyagent.transport.a2a;

import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneyProblemFamily;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.*;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.application.MoneyGrantService;
import com.example.moneyagent.application.MoneyTaskService;
import com.example.moneyagent.application.MoneyInputService;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyProjectionEntity;
import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyProjectionMapper;
import com.example.moneyagent.persistence.mapper.MoneyReportMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * A2A 协议适配器
 * <p>
 * 遵循协议与应用接口分离架构：
 * 1. 负责 JSON-RPC 2.0 (SendMessage / GetTask / CancelTask) 协议封包与解包
 * 2. 校验双层安全认证头 (Authorization: Bearer + X-Money-Delegation)
 * 3. 校验请求 Schema、字段长度、白名单枚举与数组上限
 * 4. 组装标准 A2A Task、Message、Artifact 及 metadata 回执 (mall.money.receipt.v1)
 * </p>
 */
@Slf4j
@Component
public class MoneyA2aAdapter {

    private final MoneyCaseService caseService;
    private final MoneyGrantService grantService;
    private final MoneyTaskService taskService;
    private final MoneyInputService inputService;
    private final MoneyTaskMapper taskMapper;
    private final MoneyCaseMapper caseMapper;
    private final MoneyProjectionMapper projectionMapper;
    private final MoneyReportMapper reportMapper;
    private final MoneyTaskStateMapper stateMapper;
    private final ObjectMapper objectMapper;

    public MoneyA2aAdapter(MoneyCaseService caseService,
                           MoneyGrantService grantService,
                           MoneyTaskService taskService,
                           MoneyInputService inputService,
                           MoneyTaskMapper taskMapper,
                           MoneyCaseMapper caseMapper,
                           MoneyProjectionMapper projectionMapper,
                           MoneyReportMapper reportMapper,
                           MoneyTaskStateMapper stateMapper,
                           ObjectMapper objectMapper) {
        this.caseService = caseService;
        this.grantService = grantService;
        this.taskService = taskService;
        this.inputService = inputService;
        this.taskMapper = taskMapper;
        this.caseMapper = caseMapper;
        this.projectionMapper = projectionMapper;
        this.reportMapper = reportMapper;
        this.stateMapper = stateMapper;
        this.objectMapper = objectMapper;
    }

    public MoneyA2aAdapter(MoneyCaseService caseService,
                           MoneyGrantService grantService,
                           MoneyTaskService taskService,
                           MoneyInputService inputService,
                           MoneyTaskMapper taskMapper,
                           MoneyCaseMapper caseMapper,
                           MoneyProjectionMapper projectionMapper,
                           MoneyTaskStateMapper stateMapper,
                           ObjectMapper objectMapper) {
        this(caseService, grantService, taskService, inputService, taskMapper, caseMapper, projectionMapper, null, stateMapper, objectMapper);
    }

    public MoneyA2aAdapter(MoneyCaseService caseService,
                           MoneyGrantService grantService,
                           MoneyTaskService taskService,
                           MoneyInputService inputService,
                           MoneyTaskMapper taskMapper,
                           MoneyCaseMapper caseMapper,
                           MoneyTaskStateMapper stateMapper,
                           ObjectMapper objectMapper) {
        this(caseService, grantService, taskService, inputService, taskMapper, caseMapper, null, null, stateMapper, objectMapper);
    }

    public Map<String, Object> handleRpc(Map<String, Object> requestBody, String authHeader, String delegationHeader) {
        Object id = requestBody.get("id");
        String method = (String) requestBody.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) requestBody.getOrDefault("params", Collections.emptyMap());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        try {
            // 1. 双层安全鉴权验证
            MoneySecuritySupport.VerifiedMoneyPrincipal principal = authenticateCaller(authHeader, delegationHeader, params);

            // 2. 协议方法分发
            if ("SendMessage".equalsIgnoreCase(method)) {
                return handleSendMessage(response, id, params, principal);
            } else if ("GetTask".equalsIgnoreCase(method)) {
                return handleGetTask(response, id, params, principal);
            } else if ("CancelTask".equalsIgnoreCase(method)) {
                return handleCancelTask(response, id, params, principal);
            } else if ("money.start".equalsIgnoreCase(method) || "money.investigate.v1".equalsIgnoreCase(method)) {
                // 兼容旧接口
                return handleLegacyStart(response, id, params, principal);
            } else if ("money.getCase".equalsIgnoreCase(method) || "money.case-status.v1".equalsIgnoreCase(method)) {
                // 兼容旧接口
                return handleLegacyGetCase(response, id, params, principal);
            } else if ("money.provide-input.v1".equalsIgnoreCase(method)) {
                return handleLegacyProvideInput(response, id, params, principal);
            } else if ("money.cancel-task.v1".equalsIgnoreCase(method)) {
                return handleLegacyCancel(response, id, params, principal);
            } else {
                return makeError(response, -32601, "Method not found: " + method, "METHOD_NOT_FOUND", false, String.valueOf(id));
            }
        } catch (IllegalArgumentException e) {
            log.warn("RPC 请求参数或白名单校验失败, method={}, error={}", method, e.getMessage());
            return makeError(response, -32602, e.getMessage(), "INVALID_ARGUMENT", false, String.valueOf(id));
        } catch (SecurityException e) {
            log.warn("RPC 请求安全认证失败, method={}, error={}", method, e.getMessage());
            return makeError(response, -32001, e.getMessage(), "UNAUTHORIZED", false, String.valueOf(id));
        } catch (Exception e) {
            log.error("RPC 执行异常, method={}", method, e);
            return makeError(response, -32000, e.getMessage() != null ? e.getMessage() : "Internal Error",
                    "INTERNAL_ERROR", true, String.valueOf(id));
        }
    }

    // =========================================================================
    // 标准 A2A 协议实现
    // =========================================================================

    private Map<String, Object> handleSendMessage(Map<String, Object> response, Object id, Map<String, Object> params,
                                                 MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) params.get("message");
        if (message == null) {
            throw new IllegalArgumentException("SendMessage 缺少 message 参数");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) message.getOrDefault("parts", Collections.emptyList());
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("SendMessage message.parts 不能为空");
        }

        Map<String, Object> dataPart = null;
        for (Map<String, Object> part : parts) {
            if ("data".equalsIgnoreCase((String) part.get("kind"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) part.get("data");
                dataPart = d;
                break;
            }
        }

        if (dataPart == null) {
            throw new IllegalArgumentException("未找到可解析的 data Part 业务载荷");
        }

        String operation = (String) dataPart.get("operation");
        String schema = (String) dataPart.get("schema");

        // 场景 A: GET_CASE 查询案件即时状态 (返回 Message，不新建调查 Task)
        if ("GET_CASE".equalsIgnoreCase(operation) || "money.case-status.v1".equalsIgnoreCase(schema)) {
            String caseNo = (String) dataPart.get("caseNo");
            if (caseNo == null || caseNo.isBlank()) {
                throw new IllegalArgumentException("GET_CASE 必须指定 caseNo");
            }
            MoneyCaseService.CaseDetailResult detail = caseService.getCaseDetail(caseNo);
            if (detail == null) {
                return makeError(response, -32602, "案件不存在: " + caseNo, "CASE_NOT_FOUND", false, String.valueOf(id));
            }

            MoneyCaseEntity c = detail.caseEntity();
            MoneyTaskEntity t = detail.taskEntity();
            MoneyReportEntity r = detail.reportEntity();

            // 严格对象授权核验：校验调用方身份与案件主体的授权关系
            if (!isCallerAuthorizedForCase(c, t, principal)) {
                return makeError(response, -32001, "无权访问此案件: " + caseNo, "FORBIDDEN_CASE_ACCESS", false, String.valueOf(id));
            }

            MoneyProjectionEntity proj = null;
            String role = principal != null && principal.getActorRole() != null ? principal.getActorRole().toUpperCase() : "BUYER";
            boolean isStaffOrFinance = "FINANCE".equals(role) || "ADMIN".equals(role) || "STAFF".equals(role);
            if (projectionMapper != null) {
                // 严格受众隔离：BUYER 查询 BUYER 投影，FINANCE/STAFF 查询 FINANCE 投影，严禁跨受众混用或回退
                String targetAudience = isStaffOrFinance ? "FINANCE" : "BUYER";
                proj = projectionMapper.selectOne(new LambdaQueryWrapper<MoneyProjectionEntity>()
                        .eq(MoneyProjectionEntity::getCaseNo, c.getCaseNo())
                        .eq(MoneyProjectionEntity::getAudienceScope, targetAudience)
                        .orderByDesc(MoneyProjectionEntity::getRevision)
                        .last("LIMIT 1"));
            }

            String publicSummary = "正在调查核实中";
            Long projRev = 1L;
            if (proj != null) {
                projRev = proj.getRevision();
                try {
                    com.fasterxml.jackson.databind.JsonNode pNode = objectMapper.readTree(proj.getPublicJson());
                    if (pNode.has("publicSummary")) publicSummary = pNode.get("publicSummary").asText();
                } catch (Exception ignored) {}
            } else if (r != null && isStaffOrFinance) {
                publicSummary = "资金调查报告已就绪";
            }

            Map<String, Object> caseData = new LinkedHashMap<>();
            caseData.put("caseNo", c.getCaseNo());
            caseData.put("currentTaskNo", c.getCurrentTaskNo());
            caseData.put("scopeKey", c.getScopeKey());
            caseData.put("orderNo", c.getOrderNo());
            caseData.put("caseState", c.getState());
            caseData.put("taskState", t != null ? t.getState() : "COMPLETED");
            caseData.put("resolutionOutcome", c.getOutcome());
            caseData.put("reportRef", (isStaffOrFinance && r != null) ? r.getReportNo() : null);
            caseData.put("publicSummary", publicSummary);
            caseData.put("caseVersion", c.getVersion());
            caseData.put("taskVersion", t != null ? t.getVersion() : 1L);
            caseData.put("projectionRevision", projRev);
            caseData.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : Instant.now().toString());

            Map<String, Object> messageResult = new LinkedHashMap<>();
            messageResult.put("role", "agent");
            messageResult.put("parts", List.of(Map.of("kind", "data", "data", caseData)));
            response.put("result", messageResult);
            return response;
        }

        // 场景 B: 补充输入 (mall.money.additional-input.v1)
        if (MoneyAdditionalInput.SCHEMA_V1.equalsIgnoreCase(schema)) {
            String taskNo = (String) dataPart.get("taskNo");
            if (taskNo != null && !taskNo.isBlank()) {
                MoneyTaskEntity taskEntity = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                        .eq(MoneyTaskEntity::getTaskNo, taskNo.trim()));
                if (taskEntity != null) {
                    MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                            .eq(MoneyCaseEntity::getCaseNo, taskEntity.getCaseNo()));
                    if (!isCallerAuthorizedForCase(caseEntity, taskEntity, principal)) {
                        return makeError(response, -32001, "无权对此任务补充材料: " + taskNo, "FORBIDDEN_TASK_ACCESS", false, String.valueOf(id));
                    }
                }
            }

            String inputRequestNo = (String) dataPart.get("inputRequestNo");
            Long expectedInputVersion = null;
            if (dataPart.get("expectedInputVersion") != null) {
                expectedInputVersion = ((Number) dataPart.get("expectedInputVersion")).longValue();
            } else if (dataPart.get("inputVersion") != null) {
                expectedInputVersion = ((Number) dataPart.get("inputVersion")).longValue();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> answers = (Map<String, Object>) dataPart.get("answers");
            if (answers == null) {
                answers = (Map<String, Object>) dataPart.get("data");
            }

            MoneyAdditionalInput addInput = MoneyAdditionalInput.builder()
                    .taskNo(taskNo)
                    .inputRequestNo(inputRequestNo)
                    .expectedInputVersion(expectedInputVersion)
                    .answers(answers)
                    .build();

            String caller = principal != null ? principal.getCallerService() : "mall-agent-service";
            String reqId = (String) dataPart.getOrDefault("requestId", "REQ_INP_" + UUID.randomUUID().toString().substring(0, 8));
            inputService.submitAdditionalInput(caller, reqId, addInput);

            Map<String, Object> taskResult = new LinkedHashMap<>();
            taskResult.put("id", taskNo);
            taskResult.put("state", "working");
            taskResult.put("metadata", Map.of("mall.money.view.v1", Map.of("publicSummary", "补充输入材料已受理并恢复执行")));
            response.put("result", taskResult);
            return response;
        }

        // 场景 C: 发起调查 (mall.money.investigation-request.v1)
        return handleStartInvestigation(response, id, dataPart, principal);
    }

    private Map<String, Object> handleStartInvestigation(Map<String, Object> response, Object id,
                                                         Map<String, Object> dataPart,
                                                         MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String requestId = (String) dataPart.get("requestId");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("requestId 必填且长度须在 1~128 之间");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) dataPart.get("subject");
        String orderNo = subject != null ? (String) subject.get("orderNo") : (String) dataPart.get("orderNo");
        String scopeKey = subject != null ? (String) subject.get("scopeKey") : null;
        if (scopeKey == null || scopeKey.isBlank()) {
            if (orderNo != null && !orderNo.isBlank()) {
                scopeKey = "ORDER:" + orderNo.trim();
            } else {
                throw new IllegalArgumentException("subject 中缺少 orderNo 或 scopeKey");
            }
        }

        Object rawProblem = dataPart.get("problemFamily");
        String problemFamilyStr = rawProblem != null ? String.valueOf(rawProblem) : null;
        // 严格问题分类白名单校验，未知值拒绝 (Item 5)
        MoneyProblemFamily problemFamily = MoneyProblemFamily.fromString(problemFamilyStr);

        // 精确调查目标提取：支持 attemptNo, refundNo, diffNo 按问题族优先级精确关联
        String targetRef = null;
        if (subject != null) {
            if (problemFamily == MoneyProblemFamily.REFUND_STATUS && subject.get("refundNo") != null) {
                targetRef = (String) subject.get("refundNo");
            } else if (problemFamily == MoneyProblemFamily.RECON_MISMATCH && subject.get("diffNo") != null) {
                targetRef = (String) subject.get("diffNo");
            } else if (subject.get("attemptNo") != null) {
                targetRef = (String) subject.get("attemptNo");
            } else if (subject.get("refundNo") != null) {
                targetRef = (String) subject.get("refundNo");
            } else if (subject.get("diffNo") != null) {
                targetRef = (String) subject.get("diffNo");
            }
        }
        if (targetRef == null || targetRef.isBlank()) {
            targetRef = orderNo != null ? orderNo : scopeKey;
        }

        String origin = (String) dataPart.getOrDefault("origin", "CUSTOMER");
        Long actorUserId = principal != null && principal.getActorUserId() != null ? principal.getActorUserId() : 0L;
        String actorRole = principal != null && principal.getActorRole() != null ? principal.getActorRole() : "BUYER";
        String callerService = principal != null && principal.getCallerService() != null ? principal.getCallerService() : "mall-agent-service";
        String grantRef = (String) dataPart.getOrDefault("delegationRef", principal != null ? principal.getGrantId() : null);

        MoneyInvestigationRequest originalReq = null;
        try {
            originalReq = objectMapper.convertValue(dataPart, MoneyInvestigationRequest.class);
        } catch (Exception ignored) {}

        MoneyCaseService.CaseExecutionResult execResult = caseService.createOrAttachCase(
                scopeKey, problemFamily.name(), targetRef, origin, actorUserId, actorRole,
                callerService, requestId, grantRef, originalReq);

        MoneyCaseEntity caseEntity = execResult.caseEntity();
        MoneyTaskEntity taskEntity = execResult.taskEntity();
        MoneyReportEntity reportEntity = execResult.reportEntity();

        String nowIso = Instant.now().toString();

        // 构造受理回执 metadata.mall.money.receipt.v1
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", 1);
        receipt.put("requestId", requestId);
        receipt.put("caseNo", caseEntity.getCaseNo());
        receipt.put("taskNo", taskEntity != null ? taskEntity.getTaskNo() : null);
        receipt.put("contextId", taskEntity != null ? taskEntity.getContextId() : null);
        receipt.put("acceptedAt", nowIso);
        receipt.put("reused", !execResult.isNew());
        receipt.put("projectionRevision", 1L);

        // 构造调用方视图 metadata.mall.money.view.v1
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("caseNo", caseEntity.getCaseNo());
        view.put("caseState", caseEntity.getState());
        view.put("taskState", taskEntity != null ? taskEntity.getState() : "COMPLETED");
        view.put("resolutionOutcome", caseEntity.getOutcome());
        view.put("publicSummary", reportEntity != null ? "资金调查报告已就绪" : "资金核查任务已受理并排队调查");
        view.put("reportRef", reportEntity != null ? reportEntity.getReportNo() : null);
        view.put("taskVersion", taskEntity != null ? taskEntity.getVersion() : 1L);
        view.put("caseVersion", caseEntity.getVersion());
        view.put("projectionRevision", 1L);
        view.put("updatedAt", caseEntity.getUpdatedAt().toString());

        // 构造标准 A2A Task 响应对象
        Map<String, Object> taskObj = new LinkedHashMap<>();
        taskObj.put("id", taskEntity != null ? taskEntity.getTaskNo() : "MT_COMPLETED");
        taskObj.put("contextId", taskEntity != null ? taskEntity.getContextId() : null);
        taskObj.put("state", stateMapper.toA2aState(taskEntity != null ? taskEntity.getState() : "COMPLETED"));
        taskObj.put("createdAt", caseEntity.getCreatedAt().toString());
        taskObj.put("updatedAt", caseEntity.getUpdatedAt().toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mall.money.receipt.v1", receipt);
        metadata.put("mall.money.view.v1", view);
        taskObj.put("metadata", metadata);

        // 如果调查已产出报告，作为 Artifact 返回
        if (reportEntity != null) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("id", reportEntity.getReportNo());
            artifact.put("name", "资金调查报告");
            artifact.put("parts", List.of(Map.of("kind", "text", "text", "调查结论: " + reportEntity.getOutcome())));
            taskObj.put("artifacts", List.of(artifact));
        }

        response.put("result", taskObj);
        return response;
    }

    private Map<String, Object> handleGetTask(Map<String, Object> response, Object id, Map<String, Object> params,
                                              MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String taskId = (String) params.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("GetTask 必须指定 taskId");
        }

        MoneyTaskView taskView = taskService.getTaskView(taskId.trim());
        if (taskView == null) {
            return makeError(response, -32602, "任务不存在: " + taskId, "TASK_NOT_FOUND", false, String.valueOf(id));
        }

        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, taskView.getCaseNo()));

        MoneyTaskEntity taskEntity = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getTaskNo, taskView.getTaskNo()));

        // 严格对象授权核验：校验调用方身份与任务主体的授权关系
        if (!isCallerAuthorizedForCase(caseEntity, taskEntity, principal)) {
            return makeError(response, -32001, "无权访问此任务: " + taskId, "FORBIDDEN_TASK_ACCESS", false, String.valueOf(id));
        }

        // 精确关联历史任务对应的独立报告版本，绝不能用案件最新重调查报告或固定版本篡改历史任务视图
        MoneyReportEntity taskReport = null;
        if (reportMapper != null) {
            taskReport = reportMapper.selectOne(new LambdaQueryWrapper<MoneyReportEntity>()
                    .eq(MoneyReportEntity::getTaskNo, taskView.getTaskNo())
                    .orderByDesc(MoneyReportEntity::getRevision)
                    .last("LIMIT 1"));
        }

        String outcome;
        String reportRef;
        Long projRev;
        String publicSummary;
        if (taskReport != null) {
            outcome = taskReport.getOutcome();
            reportRef = taskReport.getReportNo();
            projRev = taskReport.getRevision();
            publicSummary = "任务已完成: " + outcome;
        } else if (caseEntity != null && taskView.getTaskNo().equals(caseEntity.getCurrentTaskNo()) && "COMPLETED".equals(taskView.getTaskState())) {
            outcome = caseEntity.getOutcome() != null ? caseEntity.getOutcome() : "UNDETERMINED";
            reportRef = caseEntity.getLatestReportNo();
            projRev = 1L;
            publicSummary = "任务当前状态: " + taskView.getTaskState();
        } else {
            outcome = "UNDETERMINED";
            reportRef = null;
            projRev = 1L;
            publicSummary = "任务当前状态: " + taskView.getTaskState();
        }

        Map<String, Object> taskObj = new LinkedHashMap<>();
        taskObj.put("id", taskView.getTaskNo());
        taskObj.put("contextId", taskView.getContextId());
        taskObj.put("state", stateMapper.toA2aState(taskView.getTaskState()));
        taskObj.put("updatedAt", taskView.getUpdatedAt());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("caseNo", caseEntity != null ? caseEntity.getCaseNo() : taskView.getCaseNo());
        view.put("caseState", caseEntity != null ? caseEntity.getState() : "OPEN");
        view.put("taskState", taskView.getTaskState());
        view.put("resolutionOutcome", outcome);
        view.put("reportRef", reportRef);
        view.put("publicSummary", publicSummary);
        view.put("taskVersion", taskView.getTaskVersion());
        view.put("caseVersion", caseEntity != null ? caseEntity.getVersion() : 1L);
        view.put("projectionRevision", projRev);
        view.put("updatedAt", taskView.getUpdatedAt());

        taskObj.put("metadata", Map.of("mall.money.view.v1", view));
        response.put("result", taskObj);
        return response;
    }

    private Map<String, Object> handleCancelTask(Map<String, Object> response, Object id, Map<String, Object> params,
                                                 MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String taskId = (String) params.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("CancelTask 必须指定 taskId");
        }

        MoneyTaskEntity taskEntity = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getTaskNo, taskId.trim()));
        if (taskEntity != null) {
            MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                    .eq(MoneyCaseEntity::getCaseNo, taskEntity.getCaseNo()));
            if (!isCallerAuthorizedForCase(caseEntity, taskEntity, principal)) {
                return makeError(response, -32001, "无权取消此任务: " + taskId, "FORBIDDEN_TASK_ACCESS", false, String.valueOf(id));
            }
        }

        String caller = principal != null ? principal.getCallerService() : "mall-agent-service";
        String reqId = (String) params.getOrDefault("requestId", "REQ_CANCEL_" + UUID.randomUUID().toString().substring(0, 8));
        String reason = (String) params.getOrDefault("reason", "用户或调用方取消");
        Long expVersion = params.get("expectedTaskVersion") != null ? ((Number) params.get("expectedTaskVersion")).longValue() : null;

        MoneyCancelRequest cancelReq = MoneyCancelRequest.builder()
                .taskNo(taskId.trim())
                .expectedTaskVersion(expVersion)
                .reason(reason)
                .build();

        MoneyCancelReceipt receipt = taskService.cancelTask(caller, reqId, cancelReq);

        Map<String, Object> taskObj = new LinkedHashMap<>();
        taskObj.put("id", receipt.getTaskNo());
        taskObj.put("state", stateMapper.toA2aState(receipt.getTaskState()));
        taskObj.put("metadata", Map.of("message", receipt.getMessage() != null ? receipt.getMessage() : ""));
        response.put("result", taskObj);
        return response;
    }

    // =========================================================================
    // 兼容原有方法的映射
    // =========================================================================

    private Map<String, Object> handleLegacyStart(Map<String, Object> response, Object id, Map<String, Object> params,
                                                  MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String orderNo = (String) params.get("orderNo");
        String rawProblem = (String) params.getOrDefault("problemFamily", "PAYMENT_CONFIRMATION");
        MoneyProblemFamily family = MoneyProblemFamily.fromString(rawProblem);
        String targetRef = (String) params.getOrDefault("targetRef", orderNo);
        Number userIdNum = (Number) params.get("userId");
        Long userId = userIdNum != null ? userIdNum.longValue() : (principal != null ? principal.getActorUserId() : 0L);
        String callerRole = (String) params.getOrDefault("role", principal != null ? principal.getActorRole() : "BUYER");

        MoneyCaseService.CaseExecutionResult execResult = caseService.createOrAttachCase(
                "ORDER:" + orderNo, family.name(), targetRef, "CUSTOMER", userId, callerRole);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseNo", execResult.caseEntity().getCaseNo());
        result.put("taskNo", execResult.taskEntity() != null ? execResult.taskEntity().getTaskNo() : null);
        result.put("caseState", execResult.caseEntity().getState());
        result.put("taskState", execResult.taskEntity() != null ? execResult.taskEntity().getState() : "COMPLETED");
        result.put("outcome", execResult.caseEntity().getOutcome());
        result.put("isNew", execResult.isNew());
        if (execResult.reportEntity() != null) {
            result.put("reportNo", execResult.reportEntity().getReportNo());
            result.put("factsJson", execResult.reportEntity().getInternalJson());
        }
        response.put("result", result);
        return response;
    }

    private Map<String, Object> handleLegacyGetCase(Map<String, Object> response, Object id, Map<String, Object> params,
                                                    MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String caseNo = (String) params.get("caseNo");
        MoneyCaseService.CaseDetailResult detail = caseService.getCaseDetail(caseNo);
        if (detail == null) {
            return makeError(response, -32602, "案件不存在: " + caseNo, "CASE_NOT_FOUND", false, String.valueOf(id));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        MoneyCaseEntity c = detail.caseEntity();
        MoneyTaskEntity t = detail.taskEntity();
        MoneyReportEntity r = detail.reportEntity();

        result.put("caseNo", c.getCaseNo());
        result.put("scopeKey", c.getScopeKey());
        result.put("orderNo", c.getOrderNo());
        result.put("caseState", c.getState());
        result.put("outcome", c.getOutcome());
        result.put("taskNo", t != null ? t.getTaskNo() : null);
        result.put("taskState", t != null ? t.getState() : null);
        if (r != null) {
            result.put("reportNo", r.getReportNo());
            result.put("outcome", r.getOutcome());
            result.put("factsJson", r.getInternalJson());
        }
        response.put("result", result);
        return response;
    }

    private Map<String, Object> handleLegacyProvideInput(Map<String, Object> response, Object id, Map<String, Object> params,
                                                         MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String taskNo = (String) params.get("taskNo");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) params.getOrDefault("data", Collections.emptyMap());
        String caller = principal != null ? principal.getCallerService() : "mall-agent-service";
        String reqId = "REQ_LEGACY_INP_" + UUID.randomUUID().toString().substring(0, 8);

        MoneyAdditionalInput input = MoneyAdditionalInput.builder()
                .taskNo(taskNo)
                .answers(data)
                .build();
        boolean ok = inputService.submitAdditionalInput(caller, reqId, input);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", ok);
        result.put("message", "补充调查材料已受理并恢复执行");
        response.put("result", result);
        return response;
    }

    private Map<String, Object> handleLegacyCancel(Map<String, Object> response, Object id, Map<String, Object> params,
                                                   MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        String taskNo = (String) params.get("taskNo");
        if (taskNo != null && !taskNo.isBlank()) {
            String caller = principal != null ? principal.getCallerService() : "mall-agent-service";
            String reqId = "REQ_LEGACY_CANCEL_" + UUID.randomUUID().toString().substring(0, 8);
            MoneyCancelReceipt receipt = taskService.cancelTask(caller, reqId, MoneyCancelRequest.builder().taskNo(taskNo).build());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("canceled", true);
            result.put("taskState", receipt.getTaskState());
            response.put("result", result);
            return response;
        }

        String caseNo = (String) params.get("caseNo");
        boolean closed = caseService.closeCase(caseNo, "用户/调用方取消调查", 0L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canceled", closed);
        response.put("result", result);
        return response;
    }

    // =========================================================================
    // 鉴权与辅助工具
    // =========================================================================

    private MoneySecuritySupport.VerifiedMoneyPrincipal authenticateCaller(String authHeader, String delegationHeader,
                                                                         Map<String, Object> params) {
        String serviceToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            serviceToken = authHeader.substring(7).trim();
        }

        if (serviceToken == null || serviceToken.isBlank()) {
            throw new SecurityException("未提供服务身份凭据或未携带 Authorization: Bearer 头");
        }

        MoneySecuritySupport.ServiceIdentityClaims svcClaims =
                MoneySecuritySupport.verifyAndParseServiceIdentity(serviceToken, null, "mall-money-services");
        if (svcClaims == null) {
            throw new SecurityException("服务身份证明无效、已过期或受众不匹配");
        }

        String delegationToken = delegationHeader != null ? delegationHeader.trim() : null;
        MoneySecuritySupport.DelegationClaims delClaims = null;
        if (delegationToken != null && !delegationToken.isBlank()) {
            delClaims = MoneySecuritySupport.verifyAndParseDelegation(
                    delegationToken, null, null, null, "mall-money-services", null);
            if (delClaims == null) {
                throw new SecurityException("委托授权证明无效、已过期或受众不匹配");
            }
        }

        return new MoneySecuritySupport.VerifiedMoneyPrincipal(
                svcClaims.getServiceName(),
                delClaims != null ? delClaims.getUserId() : 0L,
                delClaims != null ? delClaims.getRole() : "SERVICE",
                delClaims != null ? delClaims.getScopeKey() : "",
                delClaims != null ? delClaims.getGrantId() : null,
                delClaims != null ? delClaims.getAllowedActions() : List.of()
        );
    }

    private boolean isCallerAuthorizedForCase(MoneyCaseEntity c, MoneyTaskEntity t, MoneySecuritySupport.VerifiedMoneyPrincipal principal) {
        if (principal == null || c == null) return false;

        // 核心安全约束：撤回授权或过期后，必须同步停止对外披露结果
        if (grantService != null) {
            if (c.getGrantRef() != null && !grantService.isGrantActive(c.getGrantRef())) {
                log.warn("案件关联的业务 Grant 已失效或被撤回，严禁继续对外披露案件/任务信息: caseNo={}, grantRef={}",
                        c.getCaseNo(), c.getGrantRef());
                return false;
            }
            if (principal.getGrantId() != null && !grantService.isGrantActive(principal.getGrantId())) {
                log.warn("调用方提供的委托凭据 Grant 已失效或被撤回: principalGrantId={}", principal.getGrantId());
                return false;
            }
        }

        String role = principal.getActorRole() != null ? principal.getActorRole().toUpperCase() : "BUYER";
        // 财务/管理员角色必须提供有效委托凭据，严禁服务默认放行
        if ("FINANCE".equals(role) || "ADMIN".equals(role) || "STAFF".equals(role)) {
            if (principal.getGrantId() != null || (principal.getActorUserId() != null && principal.getActorUserId() > 0)) {
                return true;
            }
        }
        // 买家主体授权校验
        if (principal.getGrantId() != null && principal.getGrantId().equals(c.getGrantRef())) {
            return true;
        }
        if (principal.getScopeKey() != null && !principal.getScopeKey().isBlank()
                && principal.getScopeKey().equalsIgnoreCase(c.getScopeKey())) {
            return true;
        }
        if (grantService != null) {
            String audienceScope = role + ":" + principal.getActorUserId();
            if (t != null && grantService.validateTaskAccess(t.getTaskNo(), c.getGrantRef(), principal.getCallerService(), audienceScope)) {
                return true;
            }
            if (c.getCurrentTaskNo() != null && grantService.validateTaskAccess(c.getCurrentTaskNo(), c.getGrantRef(), principal.getCallerService(), audienceScope)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> makeError(Map<String, Object> response, int rpcCode, String message,
                                         String bizCode, boolean retryable, String traceId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", rpcCode);
        error.put("message", message);
        error.put("data", Map.of(
                "code", bizCode,
                "retryable", retryable,
                "traceId", traceId
        ));
        response.put("error", error);
        return response;
    }
}

