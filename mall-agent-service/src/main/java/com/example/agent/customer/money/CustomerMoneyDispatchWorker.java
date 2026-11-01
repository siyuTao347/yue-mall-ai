package com.example.agent.customer.money;

import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneyProblemFamily;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客服端异步资金派发 Worker
 * 扫描处于 PENDING / RETRY_WAIT / UNKNOWN 状态的 Dispatch 记录并执行提交与本地事务更新
 */
@Slf4j
@Component
public class CustomerMoneyDispatchWorker {

    private final AgentMoneyDispatchMapper dispatchMapper;
    private final AgentMoneyBindingMapper bindingMapper;
    private final MoneyAgentClient moneyAgentClient;
    private final CustomerMoneyDeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final String workerId = "DISPATCH_WORKER_" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    public CustomerMoneyDispatchWorker(AgentMoneyDispatchMapper dispatchMapper,
                                       AgentMoneyBindingMapper bindingMapper,
                                       MoneyAgentClient moneyAgentClient,
                                       @Autowired(required = false) CustomerMoneyDeliveryService deliveryService,
                                       @Autowired(required = false) ObjectMapper objectMapper,
                                       @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.dispatchMapper = dispatchMapper;
        this.bindingMapper = bindingMapper;
        this.moneyAgentClient = moneyAgentClient;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public CustomerMoneyDispatchWorker(AgentMoneyDispatchMapper dispatchMapper,
                                       AgentMoneyBindingMapper bindingMapper,
                                       MoneyAgentClient moneyAgentClient,
                                       CustomerMoneyDeliveryService deliveryService,
                                       ObjectMapper objectMapper) {
        this(dispatchMapper, bindingMapper, moneyAgentClient, deliveryService, objectMapper, null);
    }

    public CustomerMoneyDispatchWorker(AgentMoneyDispatchMapper dispatchMapper,
                                       AgentMoneyBindingMapper bindingMapper,
                                       MoneyAgentClient moneyAgentClient) {
        this(dispatchMapper, bindingMapper, moneyAgentClient, null, new ObjectMapper(), null);
    }

    @Scheduled(fixedDelay = 3000)
    public void processPendingDispatches() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        // 先回收租约过期的 SENDING 派发任务（例如 Worker 异常崩溃导致停滞在 SENDING）
        recoverExpiredSendingDispatches(nowUtc);

        List<AgentMoneyDispatchEntity> pendings = dispatchMapper.selectList(new LambdaQueryWrapper<AgentMoneyDispatchEntity>()
                .in(AgentMoneyDispatchEntity::getState, List.of("PENDING", "RETRY_WAIT", "UNKNOWN"))
                .le(AgentMoneyDispatchEntity::getNextAttemptAt, nowUtc)
                .last("LIMIT 10"));

        if (pendings == null || pendings.isEmpty()) {
            return;
        }

        for (AgentMoneyDispatchEntity dispatch : pendings) {
            try {
                dispatchOne(dispatch);
            } catch (Exception e) {
                log.warn("处理异步派发任务失败, dispatchNo={}", dispatch.getDispatchNo(), e);
            }
        }
    }

    private void recoverExpiredSendingDispatches(LocalDateTime nowUtc) {
        List<AgentMoneyDispatchEntity> expiredSending = dispatchMapper.selectList(new LambdaQueryWrapper<AgentMoneyDispatchEntity>()
                .eq(AgentMoneyDispatchEntity::getState, "SENDING")
                .le(AgentMoneyDispatchEntity::getLeaseUntil, nowUtc)
                .last("LIMIT 10"));

        if (expiredSending == null || expiredSending.isEmpty()) {
            return;
        }

        for (AgentMoneyDispatchEntity d : expiredSending) {
            log.warn("恢复租约过期的 SENDING 派发任务, dispatchNo={}, workerId={}", d.getDispatchNo(), d.getLeaseOwner());
            int attempts = d.getAttempts() != null ? d.getAttempts() + 1 : 1;
            d.setAttempts(attempts);
            d.setLeaseOwner(null);
            d.setLeaseUntil(null);
            d.setLastError("LEASE_EXPIRED_DURING_SENDING");
            if (attempts >= 5) {
                d.setState("REJECTED");
                d.setNextAttemptAt(null);
            } else {
                d.setState("RETRY_WAIT");
                long delaySec = (long) Math.min(300, Math.pow(2, attempts) * 5);
                d.setNextAttemptAt(nowUtc.plusSeconds(delaySec));
            }
            d.setUpdatedAt(nowUtc);
            dispatchMapper.updateById(d);
        }
    }

    public boolean dispatchOne(AgentMoneyDispatchEntity dispatch) {
        if (dispatch == null || "ACCEPTED".equals(dispatch.getState()) || "REJECTED".equals(dispatch.getState())) {
            return false;
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        dispatch.setState("SENDING");
        dispatch.setLeaseOwner(workerId);
        dispatch.setLeaseUntil(nowUtc.plusSeconds(30));
        dispatch.setFencingToken(dispatch.getFencingToken() != null ? dispatch.getFencingToken() + 1L : 1L);
        dispatch.setUpdatedAt(nowUtc);
        dispatchMapper.updateById(dispatch);

        Long uid = dispatch.getUserId() != null ? dispatch.getUserId() : 0L;
        String safeSession = dispatch.getSessionNo() != null ? dispatch.getSessionNo() : "SESSION_DEFAULT";

        // 解析 payloadJson
        String orderNo = "";
        String problemFamilyRaw = "PAYMENT_CONFIRMATION";
        String targetRef = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(dispatch.getPayloadJson(), Map.class);
            if (map != null) {
                orderNo = map.get("orderNo") != null ? map.get("orderNo").toString() : "";
                problemFamilyRaw = map.get("problemFamily") != null ? map.get("problemFamily").toString() : "PAYMENT_CONFIRMATION";
                targetRef = map.get("targetRef") != null ? map.get("targetRef").toString() : orderNo;
            }
        } catch (Exception e) {
            log.warn("解析 Dispatch payloadJson 异常, dispatchNo={}", dispatch.getDispatchNo(), e);
        }

        MoneyProblemFamily problemFamily = MoneyProblemFamily.fromString(problemFamilyRaw);
        String scopeKey = "ORDER:" + orderNo;
        String grantRef = dispatch.getGrantRef() != null ? dispatch.getGrantRef() : MoneyIdGenerator.generateGrantNo();

        MoneyInvestigationRequest.Subject.SubjectBuilder subjectBuilder = MoneyInvestigationRequest.Subject.builder()
                .scopeKey(scopeKey)
                .orderNo(orderNo);
        if (targetRef != null && !targetRef.isBlank() && !targetRef.equalsIgnoreCase(orderNo)) {
            if (targetRef.startsWith("RF_") || "REFUND_STATUS".equals(problemFamily.name())) {
                subjectBuilder.refundNo(targetRef);
            } else if (targetRef.startsWith("PA_") || targetRef.startsWith("PAY_")) {
                subjectBuilder.attemptNo(targetRef);
            }
        }

        MoneyCallerCredential credential = buildCredential(uid, scopeKey, grantRef);
        MoneyInvestigationRequest invReq = MoneyInvestigationRequest.builder()
                .schema(MoneyInvestigationRequest.SCHEMA_V1)
                .requestId(dispatch.getRequestId())
                .origin("CUSTOMER")
                .subject(subjectBuilder.build())
                .problemFamily(problemFamily)
                .claim(MoneyInvestigationRequest.Claim.builder()
                        .text("客户反馈资金状态异常，请求调查核实")
                        .reportedAt(java.time.Instant.now().toString())
                        .build())
                .delegationRef(grantRef)
                .callerContext(MoneyInvestigationRequest.CallerContext.builder()
                        .dispatchNo(dispatch.getDispatchNo())
                        .customerRunNo(dispatch.getRunNo() != null ? dispatch.getRunNo() : safeSession)
                        .intentInstanceId(dispatch.getIntentInstanceId() != null ? dispatch.getIntentInstanceId() : safeSession + ":i1")
                        .build())
                .expectedScopeFingerprint(MoneySecuritySupport.computeFingerprint(scopeKey))
                .build();

        try {
            MoneyTaskReceipt receipt = null;
            try {
                receipt = moneyAgentClient.start(DelegatedRequest.of(invReq, credential));
            } catch (UnsupportedOperationException | AbstractMethodError e) {
                log.info("标准 start 接口未直接支持，回退简版直调");
                MoneyAgentClient.MoneyStartResponse startResp = moneyAgentClient.start(orderNo, problemFamily.name(), targetRef != null ? targetRef : orderNo, uid);
                if (startResp != null && startResp.caseNo() != null && startResp.taskNo() != null) {
                    receipt = MoneyTaskReceipt.builder()
                            .requestId(dispatch.getRequestId())
                            .caseNo(startResp.caseNo())
                            .taskNo(startResp.taskNo())
                            .contextId("CTX_" + startResp.caseNo().substring(2))
                            .acceptedAt(nowUtc.toString())
                            .projectionRevision(1L)
                            .build();
                }
            }

            if (receipt != null && receipt.getCaseNo() != null && receipt.getTaskNo() != null
                    && receipt.getContextId() != null) {
                acceptDispatchAndBindingInTx(dispatch, receipt, uid, orderNo, safeSession, problemFamily.name(), targetRef);
                return true;
            } else {
                handleFailure(dispatch, false, "INCOMPLETE_RECEIPT");
                return false;
            }
        } catch (MoneyAgentException e) {
            handleFailure(dispatch, e.isRetryable(), e.getCode());
            return false;
        } catch (Exception e) {
            handleFailure(dispatch, true, e.getMessage());
            return false;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void acceptDispatchAndBindingInTx(AgentMoneyDispatchEntity dispatch, MoneyTaskReceipt receipt,
                                             Long uid, String orderNo, String sessionNo,
                                             String problemFamily, String targetRef) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status ->
                    doAcceptDispatchAndBinding(dispatch, receipt, uid, orderNo, sessionNo, problemFamily, targetRef));
        } else {
            doAcceptDispatchAndBinding(dispatch, receipt, uid, orderNo, sessionNo, problemFamily, targetRef);
        }
    }

    private void doAcceptDispatchAndBinding(AgentMoneyDispatchEntity dispatch, MoneyTaskReceipt receipt,
                                           Long uid, String orderNo, String sessionNo,
                                           String problemFamily, String targetRef) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        dispatch.setState("ACCEPTED");
        dispatch.setRemoteTaskNo(receipt.getTaskNo());
        try {
            dispatch.setReceiptJson(objectMapper.writeValueAsString(receipt));
        } catch (Exception e) {
            dispatch.setReceiptJson("{\"caseNo\":\"" + receipt.getCaseNo() + "\",\"taskNo\":\"" + receipt.getTaskNo() + "\"}");
        }
        dispatch.setLeaseOwner(null);
        dispatch.setLeaseUntil(null);
        dispatch.setUpdatedAt(nowUtc);
        dispatchMapper.updateById(dispatch);

        Long revision = receipt.getProjectionRevision() != null ? receipt.getProjectionRevision() : 1L;
        String projectionJson = "{\"caseNo\":\"" + receipt.getCaseNo() + "\",\"orderNo\":\"" + orderNo + "\",\"problemFamily\":\"" + problemFamily + "\",\"targetRef\":\"" + targetRef + "\"}";

        AgentMoneyBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getUserId, uid)
                .eq(AgentMoneyBindingEntity::getCaseNo, receipt.getCaseNo()));

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
            existing.setUpdatedAt(nowUtc);
            bindingMapper.updateById(existing);
        }

        if (deliveryService != null) {
            String notice = String.format("您的资金核查请求（案件编号：%s）已由后台受理，平台正同步核查支付与账本流水。", receipt.getCaseNo());
            deliveryService.deliverProjectionUpdate(receipt.getCaseNo(), revision, uid, sessionNo, "TASK_ACCEPTED", notice, null, null);
        }
    }

    private void handleFailure(AgentMoneyDispatchEntity dispatch, boolean retryable, String errorMsg) {
        int attempts = dispatch.getAttempts() != null ? dispatch.getAttempts() + 1 : 1;
        dispatch.setAttempts(attempts);
        dispatch.setLastError(errorMsg != null ? errorMsg : "UNKNOWN_ERROR");
        dispatch.setLeaseOwner(null);
        dispatch.setLeaseUntil(null);

        if (!retryable || attempts >= 5) {
            dispatch.setState("REJECTED");
            dispatch.setNextAttemptAt(null);
        } else {
            dispatch.setState("RETRY_WAIT");
            long delaySec = (long) Math.min(300, Math.pow(2, attempts) * 5);
            dispatch.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delaySec));
        }
        dispatch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        dispatchMapper.updateById(dispatch);
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
