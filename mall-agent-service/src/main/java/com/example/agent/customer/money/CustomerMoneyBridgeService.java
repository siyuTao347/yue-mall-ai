package com.example.agent.customer.money;

import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneyProblemFamily;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CustomerMoneyBridgeService {

    private final MoneyAgentClient moneyAgentClient;
    private final AgentMoneyBindingMapper bindingMapper;
    private final AgentMoneyDispatchMapper dispatchMapper;
    private final CustomerMoneyDispatchWorker dispatchWorker;
    private final CustomerMoneyAnswerAssembler answerAssembler;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerMoneyBridgeService(MoneyAgentClient moneyAgentClient,
                                      AgentMoneyBindingMapper bindingMapper,
                                      AgentMoneyDispatchMapper dispatchMapper,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false) CustomerMoneyDispatchWorker dispatchWorker,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false) CustomerMoneyAnswerAssembler answerAssembler,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper objectMapper) {
        this.moneyAgentClient = moneyAgentClient;
        this.bindingMapper = bindingMapper;
        this.dispatchMapper = dispatchMapper;
        this.answerAssembler = answerAssembler != null ? answerAssembler : new CustomerMoneyAnswerAssembler();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.dispatchWorker = dispatchWorker != null ? dispatchWorker
                : new CustomerMoneyDispatchWorker(dispatchMapper, bindingMapper, moneyAgentClient, null, this.objectMapper);
    }

    public CustomerMoneyBridgeService(MoneyAgentClient moneyAgentClient,
                                      AgentMoneyBindingMapper bindingMapper,
                                      AgentMoneyDispatchMapper dispatchMapper) {
        this(moneyAgentClient, bindingMapper, dispatchMapper, null, new CustomerMoneyAnswerAssembler(), new ObjectMapper());
    }

    public MoneyBridgeResponse handleMoneyIntent(String intent, String sessionNo, Long userId, Map<String, String> slots) {
        String orderNo = slots != null ? slots.get("orderNo") : null;
        String caseNo = slots != null ? slots.get("moneyCaseNo") : null;
        Long uid = userId != null ? userId : 0L;
        String safeSession = sessionNo != null ? sessionNo : "SESSION_DEFAULT";

        if ("MONEY_CASE_PROGRESS".equalsIgnoreCase(intent)) {
            return queryCaseProgress(caseNo, orderNo, uid);
        }

        if (orderNo == null || orderNo.isBlank()) {
            return new MoneyBridgeResponse("请提供您需要核对资金异常的订单编号，例如：“订单 T202610310001 扣款了未到账”。", null, null);
        }

        String safeOrderNo = orderNo.trim();
        MoneyProblemFamily family = "REFUND_ANOMALY".equalsIgnoreCase(intent)
                ? MoneyProblemFamily.REFUND_STATUS
                : MoneyProblemFamily.PAYMENT_CONFIRMATION;
        String problemFamily = family.name();

        String targetRef = safeOrderNo;
        if (slots != null) {
            if (slots.containsKey("refundNo") && slots.get("refundNo") != null && !slots.get("refundNo").isBlank()) {
                targetRef = slots.get("refundNo").trim();
            } else if (slots.containsKey("attemptNo") && slots.get("attemptNo") != null && !slots.get("attemptNo").isBlank()) {
                targetRef = slots.get("attemptNo").trim();
            }
        }

        // 检查已有活跃绑定（按 orderNo + problemFamily 细分，避免同订单不同资金问题被盲目合并）
        List<AgentMoneyBindingEntity> activeBindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getUserId, uid)
                .eq(AgentMoneyBindingEntity::getOrderNo, safeOrderNo)
                .in(AgentMoneyBindingEntity::getCaseState, List.of("OPEN", "NEED_INPUT", "NEED_REVIEW", "WAITING_BIZ")));

        if (activeBindings != null && !activeBindings.isEmpty()) {
            for (AgentMoneyBindingEntity b : activeBindings) {
                // 检查是否为同问题族或相同 targetRef
                boolean matchesProblem = true;
                if (b.getProjectionJson() != null && b.getProjectionJson().contains("\"problemFamily\"")) {
                    matchesProblem = b.getProjectionJson().contains(problemFamily);
                }
                if (matchesProblem) {
                    String outcome = resolveExistingCaseOutcome(b.getCaseNo(), safeOrderNo, uid, b.getGrantRef());
                    String reply = String.format("系统正在跟进您订单 %s 的资金核查案件（编号：%s）。当前进展：%s。如有最新核查结果将自动向您同步。",
                            safeOrderNo, b.getCaseNo(), CustomerMoneyAnswerAssembler.friendlyOutcome(outcome));
                    return new MoneyBridgeResponse(reply, b.getCaseNo(), b.getTaskNo());
                }
            }
        }

        // 1. 本地登记 Dispatch 记录（先落库为 PENDING，保证唯一键与稳定 requestId）
        String dispatchNo = MoneyIdGenerator.generateDispatchNo();
        String requestId = "REQ_" + sha256(uid + ":" + safeSession + ":" + safeOrderNo + ":" + problemFamily + ":" + targetRef).substring(0, 32).toUpperCase();

        String payloadJson = "{\"orderNo\":\"" + safeOrderNo + "\",\"problemFamily\":\"" + problemFamily + "\",\"targetRef\":\"" + targetRef + "\"}";
        String requestHash = sha256(payloadJson);
        String grantRef = MoneyIdGenerator.generateGrantNo();
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        AgentMoneyDispatchEntity dispatch = AgentMoneyDispatchEntity.builder()
                .dispatchNo(dispatchNo)
                .userId(uid)
                .sessionNo(safeSession)
                .action("START_INVESTIGATION")
                .requestId(requestId)
                .requestHash(requestHash)
                .payloadJson(payloadJson)
                .grantRef(grantRef)
                .state("PENDING")
                .attempts(1)
                .nextAttemptAt(nowUtc.plusSeconds(30))
                .fencingToken(1L)
                .version(1L)
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .build();
        dispatchMapper.insert(dispatch);

        // 2. 由 DispatchWorker 执行派发（含凭据构建、A2A 提交与事务内原子更新）
        // 设置短时限超时保护，绝不长时间阻塞客服 HTTP 会话；若超时则平滑转由后台 Worker 异步认领重试
        boolean accepted;
        try {
            accepted = CompletableFuture.supplyAsync(() -> dispatchWorker.dispatchOne(dispatch))
                    .get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("同步派发超时或异常，交由后台 Worker 异步处理: dispatchNo={}, error={}", dispatchNo, e.getMessage());
            accepted = false;
        }
        if (accepted) {
            AgentMoneyBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getUserId, uid)
                    .eq(AgentMoneyBindingEntity::getOrderNo, safeOrderNo)
                    .orderByDesc(AgentMoneyBindingEntity::getId)
                    .last("LIMIT 1"));
            MoneyTaskReceipt receipt = null;
            if (dispatch.getReceiptJson() != null) {
                try {
                    receipt = objectMapper.readValue(dispatch.getReceiptJson(), MoneyTaskReceipt.class);
                } catch (Exception ignored) {}
            }
            if (receipt == null && binding != null) {
                receipt = MoneyTaskReceipt.builder()
                        .caseNo(binding.getCaseNo())
                        .taskNo(binding.getTaskNo())
                        .contextId(binding.getRemoteContextId())
                        .build();
            }
            String reply = answerAssembler.assembleDispatchAccepted(safeOrderNo, receipt);
            return new MoneyBridgeResponse(reply, receipt != null ? receipt.getCaseNo() : (binding != null ? binding.getCaseNo() : null),
                    receipt != null ? receipt.getTaskNo() : (binding != null ? binding.getTaskNo() : null));
        } else {
            String reply = answerAssembler.assembleDispatchPending(safeOrderNo, dispatchNo);
            return new MoneyBridgeResponse(reply, null, null);
        }
    }

    /**
     * 本地事务内原子更新 dispatch 与 upsert binding
     */
    @Transactional(rollbackFor = Exception.class)
    public void acceptDispatchAndUpsertBinding(AgentMoneyDispatchEntity dispatch, MoneyTaskReceipt receipt,
                                               Long uid, String orderNo, String sessionNo,
                                               String problemFamily, String targetRef) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 1. 更新 dispatch 为 ACCEPTED
        dispatch.setState("ACCEPTED");
        dispatch.setRemoteTaskNo(receipt.getTaskNo());
        try {
            dispatch.setReceiptJson(objectMapper.writeValueAsString(receipt));
        } catch (Exception ex) {
            dispatch.setReceiptJson("{\"caseNo\":\"" + receipt.getCaseNo() + "\",\"taskNo\":\"" + receipt.getTaskNo() + "\"}");
        }
        dispatch.setUpdatedAt(nowUtc);
        dispatchMapper.updateById(dispatch);

        // 2. Upsert Binding 记录，只采用真实回执字段
        AgentMoneyBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getUserId, uid)
                .eq(AgentMoneyBindingEntity::getCaseNo, receipt.getCaseNo())
                .eq(AgentMoneyBindingEntity::getAudienceScope, "BUYER:" + uid));

        Long revision = receipt.getProjectionRevision() != null ? receipt.getProjectionRevision() : 1L;
        String projectionJson = "{\"caseNo\":\"" + receipt.getCaseNo() + "\",\"orderNo\":\"" + orderNo + "\",\"problemFamily\":\"" + problemFamily + "\",\"targetRef\":\"" + targetRef + "\"}";

        if (existing == null) {
            AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                    .userId(uid)
                    .caseNo(receipt.getCaseNo())
                    .taskNo(receipt.getTaskNo())
                    .remoteContextId(receipt.getContextId())
                    .orderNo(orderNo)
                    .audienceScope("BUYER:" + uid)
                    .originSessionNo(sessionNo)
                    .currentSessionNo(sessionNo)
                    .grantRef(dispatch.getGrantRef())
                    .caseState("OPEN")
                    .taskState("SUBMITTED")
                    .subscriptionState("ACTIVE")
                    .projectionRevision(revision)
                    .projectionJson(projectionJson)
                    .version(1L)
                    .createdAt(nowUtc)
                    .updatedAt(nowUtc)
                    .build();
            bindingMapper.insert(binding);
        } else {
            existing.setTaskNo(receipt.getTaskNo());
            existing.setRemoteContextId(receipt.getContextId());
            existing.setProjectionRevision(revision);
            existing.setProjectionJson(projectionJson);
            existing.setCurrentSessionNo(sessionNo);
            existing.setUpdatedAt(nowUtc);
            bindingMapper.updateById(existing);
        }
    }

    private MoneyBridgeResponse queryCaseProgress(String caseNo, String orderNo, Long userId) {
        String safeCaseNo = caseNo != null ? caseNo.trim() : null;
        String safeOrderNo = orderNo != null ? orderNo.trim() : null;

        // 1. 同时提供 caseNo 与 orderNo 时，严格校验一致性
        if (safeCaseNo != null && !safeCaseNo.isBlank()) {
            AgentMoneyBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getCaseNo, safeCaseNo)
                    .eq(AgentMoneyBindingEntity::getUserId, userId));

            if (binding == null) {
                return new MoneyBridgeResponse("未找到与您账号关联的案件 " + safeCaseNo + "。请核对编号后重试。", null, null);
            }

            if (safeOrderNo != null && !safeOrderNo.isBlank() && !safeOrderNo.equalsIgnoreCase(binding.getOrderNo())) {
                return new MoneyBridgeResponse(
                        String.format("案件编号 %s 与所指订单号 %s 不一致（该案件实际关联订单：%s），请核对后重试。",
                                safeCaseNo, safeOrderNo, binding.getOrderNo()),
                        null, null
                );
            }

            String outcome = resolveExistingCaseOutcome(binding.getCaseNo(), binding.getOrderNo(), userId, binding.getGrantRef());
            String reply = answerAssembler.assembleBindingStatus(binding, outcome);
            return new MoneyBridgeResponse(reply, binding.getCaseNo(), binding.getTaskNo());
        }

        // 2. 仅提供 orderNo 时，查询所有活动绑定；若有多笔案件，要求用户确认候选
        if (safeOrderNo != null && !safeOrderNo.isBlank()) {
            List<AgentMoneyBindingEntity> bindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getOrderNo, safeOrderNo)
                    .eq(AgentMoneyBindingEntity::getUserId, userId)
                    .orderByDesc(AgentMoneyBindingEntity::getId));

            if (bindings == null || bindings.isEmpty()) {
                return new MoneyBridgeResponse("未找到订单 " + safeOrderNo + " 的相关资金调查案件。若有扣款异常，请发起调查核对。", null, null);
            }

            if (bindings.size() > 1) {
                StringBuilder sb = new StringBuilder("订单 " + safeOrderNo + " 存在多笔资金核查案件，请确认您要查询哪一笔：\n");
                for (int i = 0; i < bindings.size(); i++) {
                    AgentMoneyBindingEntity b = bindings.get(i);
                    sb.append(String.format("%d. 案件编号：%s（状态：%s，更新时间：%s）\n",
                            i + 1, b.getCaseNo(), b.getCaseState(), b.getUpdatedAt() != null ? b.getUpdatedAt().toString() : "最近"));
                }
                return new MoneyBridgeResponse(sb.toString().stripTrailing(), null, null);
            }

            AgentMoneyBindingEntity single = bindings.getFirst();
            String outcome = resolveExistingCaseOutcome(single.getCaseNo(), single.getOrderNo(), userId, single.getGrantRef());
            String reply = answerAssembler.assembleBindingStatus(single, outcome);
            return new MoneyBridgeResponse(reply, single.getCaseNo(), single.getTaskNo());
        }

        // 3. 既未提供 caseNo 也未提供 orderNo
        List<AgentMoneyBindingEntity> userBindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getUserId, userId)
                .in(AgentMoneyBindingEntity::getCaseState, List.of("OPEN", "NEED_INPUT", "NEED_REVIEW", "WAITING_BIZ"))
                .orderByDesc(AgentMoneyBindingEntity::getId)
                .last("LIMIT 5"));

        if (userBindings == null || userBindings.isEmpty()) {
            return new MoneyBridgeResponse("未找到与您账号关联的正在进行中的资金调查案件。若有扣款异常，请提供具体订单号。", null, null);
        }

        if (userBindings.size() > 1) {
            StringBuilder sb = new StringBuilder("检测到您名下有多笔正在进行的资金核实案件，请确认您要查询的案件或订单：\n");
            for (int i = 0; i < userBindings.size(); i++) {
                AgentMoneyBindingEntity b = userBindings.get(i);
                sb.append(String.format("%d. 案件编号：%s（关联订单：%s，状态：%s）\n",
                        i + 1, b.getCaseNo(), b.getOrderNo(), b.getCaseState()));
            }
            return new MoneyBridgeResponse(sb.toString().stripTrailing(), null, null);
        }

        AgentMoneyBindingEntity single = userBindings.getFirst();
        String outcome = resolveExistingCaseOutcome(single.getCaseNo(), single.getOrderNo(), userId, single.getGrantRef());
        String reply = answerAssembler.assembleBindingStatus(single, outcome);
        return new MoneyBridgeResponse(reply, single.getCaseNo(), single.getTaskNo());
    }

    private String resolveExistingCaseOutcome(String caseNo, String orderNo, Long userId, String grantRef) {
        String outcome = "UNDETERMINED";
        try {
            MoneyCallerCredential credential = buildCredential(userId, "ORDER:" + orderNo, grantRef);
            MoneyCaseView caseView = moneyAgentClient.getCase(MoneyCaseReference.of(caseNo), credential);
            if (caseView != null && caseView.getResolutionOutcome() != null) {
                return caseView.getResolutionOutcome();
            }
        } catch (Exception ignored) {}

        try {
            MoneyAgentClient.MoneyCaseResponse caseResp = moneyAgentClient.getCase(caseNo);
            if (caseResp != null && caseResp.outcome() != null) {
                return caseResp.outcome();
            }
        } catch (Exception ignored) {}

        return outcome;
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

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "hash_" + text.hashCode();
        }
    }

    public record MoneyBridgeResponse(String replyText, String caseNo, String taskNo) {}
}
