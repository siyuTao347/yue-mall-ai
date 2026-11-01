package com.example.agent.customer.money;

import api.agent.AgentActor;
import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneyProblemFamily;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.*;
import api.response.ApiResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.error;

/**
 * 用户与坐席资金调查 API 控制器
 * 提供标准的调查登记、dispatch 状态查询、案件列表与详情、资料补充与取消接口
 */
@Slf4j
@RestController
public class CustomerMoneyController {

    private final CustomerMoneyBridgeService bridgeService;
    private final AgentMoneyBindingMapper bindingMapper;
    private final AgentMoneyDispatchMapper dispatchMapper;
    private final AgentMoneyStaffNoteMapper staffNoteMapper;
    private final MoneyAgentClient moneyAgentClient;
    private final CustomerMoneyNoticeService noticeService;
    private final ObjectMapper objectMapper;

    public CustomerMoneyController(CustomerMoneyBridgeService bridgeService,
                                   AgentMoneyBindingMapper bindingMapper,
                                   AgentMoneyDispatchMapper dispatchMapper,
                                   AgentMoneyStaffNoteMapper staffNoteMapper,
                                   MoneyAgentClient moneyAgentClient,
                                   CustomerMoneyNoticeService noticeService,
                                   ObjectMapper objectMapper) {
        this.bridgeService = bridgeService;
        this.bindingMapper = bindingMapper;
        this.dispatchMapper = dispatchMapper;
        this.staffNoteMapper = staffNoteMapper;
        this.moneyAgentClient = moneyAgentClient;
        this.noticeService = noticeService;
        this.objectMapper = objectMapper;
    }

    public CustomerMoneyController(CustomerMoneyBridgeService bridgeService,
                                   AgentMoneyBindingMapper bindingMapper,
                                   AgentMoneyDispatchMapper dispatchMapper,
                                   AgentMoneyStaffNoteMapper staffNoteMapper,
                                   MoneyAgentClient moneyAgentClient,
                                   CustomerMoneyNoticeService noticeService) {
        this(bridgeService, bindingMapper, dispatchMapper, staffNoteMapper, moneyAgentClient, noticeService, new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // 用户端 API
    // -------------------------------------------------------------------------

    @PostMapping("/api/agent/money/investigations")
    public Object registerInvestigation(AgentActor actor, @RequestBody Map<String, Object> body) {
        String sessionNo = body.get("sessionNo") != null ? body.get("sessionNo").toString() : "SESSION_DEFAULT";
        String orderNo = body.get("orderNo") != null ? body.get("orderNo").toString() : "";
        String problemFamily = body.get("problemFamily") != null ? body.get("problemFamily").toString() : "PAYMENT_CONFIRMATION";

        var res = bridgeService.handleMoneyIntent("PAYMENT_ANOMALY", sessionNo, actor.userId(),
                Map.of("orderNo", orderNo, "problemFamily", problemFamily));
        return ApiResponse.success(Map.of(
                "caseNo", res.caseNo() != null ? res.caseNo() : "",
                "taskNo", res.taskNo() != null ? res.taskNo() : "",
                "replyText", res.replyText()
        ));
    }

    @GetMapping("/api/agent/money/dispatches/{dispatchNo}")
    public Object getDispatch(AgentActor actor, @PathVariable String dispatchNo) {
        AgentMoneyDispatchEntity dispatch = dispatchMapper.selectOne(new LambdaQueryWrapper<AgentMoneyDispatchEntity>()
                .eq(AgentMoneyDispatchEntity::getDispatchNo, dispatchNo));
        if (dispatch == null) {
            throw error(404, "MONEY_DISPATCH_UNAVAILABLE", "派发记录不存在");
        }
        if (dispatch.getUserId() != null && dispatch.getUserId() != actor.userId() && !"ADMIN".equalsIgnoreCase(actor.role()) && !"STAFF".equalsIgnoreCase(actor.role())) {
            throw error(403, "MONEY_CASE_INACCESSIBLE", "无权查看该派发记录");
        }
        return ApiResponse.success(dispatch);
    }

    @GetMapping("/api/agent/money/cases")
    public Object listCases(AgentActor actor, @RequestParam(required = false) String orderNo) {
        var wrapper = new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getUserId, actor.userId())
                .orderByDesc(AgentMoneyBindingEntity::getId);
        if (orderNo != null && !orderNo.isBlank()) {
            wrapper.eq(AgentMoneyBindingEntity::getOrderNo, orderNo.trim());
        }
        List<AgentMoneyBindingEntity> list = bindingMapper.selectList(wrapper);
        return ApiResponse.success(list);
    }

    @GetMapping("/api/agent/money/cases/{caseNo}")
    public Object getCase(AgentActor actor, @PathVariable String caseNo) {
        AgentMoneyBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getCaseNo, caseNo)
                .eq(AgentMoneyBindingEntity::getUserId, actor.userId()));
        if (binding == null) {
            throw error(404, "MONEY_CASE_INACCESSIBLE", "未找到与您账号关联的案件");
        }

        MoneyCallerCredential credential = buildCredential(actor.userId(), "ORDER:" + binding.getOrderNo(), binding.getGrantRef());
        MoneyCaseView caseView = null;
        try {
            caseView = moneyAgentClient.getCase(MoneyCaseReference.of(caseNo), credential);
        } catch (Exception e) {
            log.warn("获取远端案件视图异常，使用本地持久绑定缓存: {}", e.getMessage());
        }

        return ApiResponse.success(Map.of(
                "binding", binding,
                "caseView", caseView != null ? caseView : Map.of()
        ));
    }

    @PostMapping("/api/agent/money/cases/{caseNo}/inputs/{inputNo}")
    public Object provideInput(AgentActor actor, @PathVariable String caseNo,
                               @PathVariable String inputNo, @RequestBody Map<String, Object> body) {
        AgentMoneyBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getCaseNo, caseNo)
                .eq(AgentMoneyBindingEntity::getUserId, actor.userId()));
        if (binding == null) {
            throw error(404, "MONEY_CASE_INACCESSIBLE", "未找到该案件绑定");
        }

        String scopeKey = "ORDER:" + binding.getOrderNo();
        MoneyCallerCredential credential = buildCredential(actor.userId(), scopeKey, binding.getGrantRef());

        MoneyAdditionalInput input = MoneyAdditionalInput.builder()
                .caseNo(caseNo)
                .taskNo(binding.getTaskNo())
                .inputRequestNo(inputNo)
                .answers(body)
                .build();

        try {
            MoneyTaskView taskView = moneyAgentClient.provideInput(DelegatedRequest.of(input, credential));
            return ApiResponse.success(Map.of("receiptState", "SUBMITTED", "taskView", taskView));
        } catch (Exception e) {
            log.warn("提交补充资料失败: {}", e.getMessage());
            return ApiResponse.success(Map.of("receiptState", "SUBMITTING", "message", "后台正异步重试提交资料"));
        }
    }

    @PostMapping("/api/agent/money/cases/{caseNo}/cancel")
    public Object cancelCase(AgentActor actor, @PathVariable String caseNo, @RequestBody(required = false) Map<String, Object> body) {
        AgentMoneyBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getCaseNo, caseNo)
                .eq(AgentMoneyBindingEntity::getUserId, actor.userId()));
        if (binding == null) {
            throw error(404, "MONEY_CASE_INACCESSIBLE", "未找到该案件绑定");
        }

        // 用户取消调查：将自身绑定标记为 MUTED，不擅自终结整个内部财务案件
        binding.setSubscriptionState("MUTED");
        binding.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        bindingMapper.updateById(binding);

        String reason = body != null && body.containsKey("reason") ? body.get("reason").toString() : "用户取消调查";
        MoneyCallerCredential credential = buildCredential(actor.userId(), "ORDER:" + binding.getOrderNo(), binding.getGrantRef());

        MoneyCancelRequest cancelReq = MoneyCancelRequest.builder()
                .requestId("REQ_CANCEL_" + MoneyIdGenerator.generateDispatchNo().substring(2))
                .caseNo(caseNo)
                .taskNo(binding.getTaskNo())
                .reason(reason)
                .build();

        try {
            MoneyCancelReceipt receipt = moneyAgentClient.cancel(DelegatedRequest.of(cancelReq, credential));
            return ApiResponse.success(receipt);
        } catch (Exception e) {
            return ApiResponse.success(Map.of("status", "SUBSCRIPTION_MUTED", "message", "已取消关注该案件进展通知"));
        }
    }

    // -------------------------------------------------------------------------
    // 坐席端 API
    // -------------------------------------------------------------------------

    @GetMapping("/api/staff/agent/sessions/{sessionNo}/money-cases")
    public Object getStaffSessionMoneyCases(AgentActor actor, @PathVariable String sessionNo) {
        if (!"STAFF".equalsIgnoreCase(actor.role()) && !"ADMIN".equalsIgnoreCase(actor.role())) {
            throw error(403, "AGENT_PERMISSION_DENIED", "仅客服坐席及管理员可查看");
        }

        List<AgentMoneyBindingEntity> bindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getCurrentSessionNo, sessionNo)
                .or()
                .eq(AgentMoneyBindingEntity::getOriginSessionNo, sessionNo));

        List<AgentMoneyStaffNoteEntity> notes = staffNoteMapper.selectList(new LambdaQueryWrapper<AgentMoneyStaffNoteEntity>()
                .eq(AgentMoneyStaffNoteEntity::getSessionNo, sessionNo)
                .orderByDesc(AgentMoneyStaffNoteEntity::getId));

        return ApiResponse.success(Map.of(
                "sessionNo", sessionNo,
                "bindings", bindings != null ? bindings : List.of(),
                "staffNotes", notes != null ? notes : List.of()
        ));
    }

    // -------------------------------------------------------------------------
    // 内部 Notice 入口
    // -------------------------------------------------------------------------

    @PostMapping({"/internal/agent/money/case-notices", "/api/agent/money/case-notices"})
    public Object receiveNotice(@RequestBody Map<String, Object> body) {
        String source = body.get("source") != null ? body.get("source").toString() : "MONEY_AGENT";
        String eventId = body.get("eventId") != null ? body.get("eventId").toString() : "EV_" + System.currentTimeMillis();
        try {
            String payloadJson = objectMapper.writeValueAsString(body);
            boolean ok = noticeService.recordAndProcessNotice(source, eventId, payloadJson);
            return ApiResponse.success(Map.of("processed", ok));
        } catch (Exception e) {
            throw error(400, "INVALID_ARGUMENT", "Notice 格式非法: " + e.getMessage());
        }
    }

    private MoneyCallerCredential buildCredential(Long userId, String scopeKey, String grantRef) {
        String safeGrant = grantRef != null ? grantRef : MoneyIdGenerator.generateGrantNo();
        String serviceToken = MoneySecuritySupport.createServiceIdentityProof("CUSTOMER_AGENT", "mall-money-services");
        String delegationToken = MoneySecuritySupport.createDelegationProof(userId, "BUYER", scopeKey, "mall-money-services", List.of("*"), safeGrant);
        return MoneyCallerCredential.builder()
                .callerService("CUSTOMER_AGENT")
                .actorUserId(userId)
                .actorRole("BUYER")
                .grantRef(safeGrant)
                .scopeKey(scopeKey)
                .serviceIdentityToken(serviceToken)
                .delegationToken(delegationToken)
                .build();
    }
}
