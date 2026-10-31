package com.example.agent.customer.money;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CustomerMoneyBridgeService {

    private final MoneyAgentClient moneyAgentClient;
    private final AgentMoneyBindingMapper bindingMapper;
    private final AgentMoneyDispatchMapper dispatchMapper;

    public CustomerMoneyBridgeService(MoneyAgentClient moneyAgentClient,
                                     AgentMoneyBindingMapper bindingMapper,
                                     AgentMoneyDispatchMapper dispatchMapper) {
        this.moneyAgentClient = moneyAgentClient;
        this.bindingMapper = bindingMapper;
        this.dispatchMapper = dispatchMapper;
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

        // 检查已有活跃绑定
        AgentMoneyBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                .eq(AgentMoneyBindingEntity::getUserId, uid)
                .eq(AgentMoneyBindingEntity::getOrderNo, safeOrderNo)
                .eq(AgentMoneyBindingEntity::getCaseState, "OPEN"));

        if (existing != null) {
            MoneyAgentClient.MoneyCaseResponse caseResp = moneyAgentClient.getCase(existing.getCaseNo());
            String statusDesc = (caseResp != null && caseResp.outcome() != null)
                    ? friendlyOutcome(caseResp.outcome())
                    : "正在调查核对中";
            String reply = String.format("系统正在跟进您订单 %s 的资金核查案件（编号：%s）。当前进展：%s。如有最新核查结果将自动向您同步。",
                    safeOrderNo, existing.getCaseNo(), statusDesc);
            return new MoneyBridgeResponse(reply, existing.getCaseNo(), existing.getTaskNo());
        }

        // 1. 本地登记 Dispatch 记录（先落库再发起）
        String dispatchNo = "DISP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String requestId = "REQ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String problemFamily = "REFUND_ANOMALY".equalsIgnoreCase(intent) ? "REFUND_STATUS" : "PAYMENT_CONFIRMATION";
        String payloadJson = "{\"orderNo\":\"" + safeOrderNo + "\",\"problemFamily\":\"" + problemFamily + "\"}";
        String requestHash = sha256(payloadJson);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        AgentMoneyDispatchEntity dispatch = AgentMoneyDispatchEntity.builder()
                .dispatchNo(dispatchNo)
                .userId(uid)
                .sessionNo(safeSession)
                .action("START_INVESTIGATION")
                .requestId(requestId)
                .requestHash(requestHash)
                .payloadJson(payloadJson)
                .grantRef("GRANT_" + UUID.randomUUID().toString().substring(0, 8))
                .state("PENDING")
                .attempts(1)
                .nextAttemptAt(nowUtc.plusSeconds(30))
                .fencingToken(1L)
                .version(1L)
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .build();
        dispatchMapper.insert(dispatch);

        // 2. 发起远程调用
        try {
            MoneyAgentClient.MoneyStartResponse startResp = moneyAgentClient.start(safeOrderNo, problemFamily, safeOrderNo, uid);

            if (startResp != null && startResp.caseNo() != null) {
                // 更新 dispatch 为 COMPLETED
                dispatch.setState("COMPLETED");
                dispatch.setRemoteTaskNo(startResp.taskNo());
                dispatch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                dispatchMapper.updateById(dispatch);

                // 建立正式 Binding
                AgentMoneyBindingEntity binding = AgentMoneyBindingEntity.builder()
                        .userId(uid)
                        .caseNo(startResp.caseNo())
                        .taskNo(startResp.taskNo() != null ? startResp.taskNo() : "TASK_UNKNOWN")
                        .remoteContextId("CTX_" + UUID.randomUUID().toString().substring(0, 8))
                        .orderNo(safeOrderNo)
                        .audienceScope("BUYER:" + uid)
                        .originSessionNo(safeSession)
                        .currentSessionNo(safeSession)
                        .grantRef(dispatch.getGrantRef())
                        .caseState(startResp.caseState() != null ? startResp.caseState() : "OPEN")
                        .taskState(startResp.taskState() != null ? startResp.taskState() : "SUBMITTED")
                        .subscriptionState("ACTIVE")
                        .projectionRevision(1L)
                        .version(1L)
                        .createdAt(nowUtc)
                        .updatedAt(nowUtc)
                        .build();
                bindingMapper.insert(binding);

                String reply = String.format("已为您正式受理订单 %s 的资金核实与调查请求（案件编号：%s）。\n" +
                                "平台正通过安全通道回溯外部支付渠道、商户流水与内部资金账本，核实完毕后将自动向您汇报调查结论。",
                        safeOrderNo, startResp.caseNo());
                return new MoneyBridgeResponse(reply, startResp.caseNo(), startResp.taskNo());
            } else {
                dispatch.setState("FAILED");
                dispatch.setLastError("EMPTY_RESPONSE");
                dispatch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                dispatchMapper.updateById(dispatch);
                return new MoneyBridgeResponse("资金调查服务已记录您的请求，正由后台异步排队核查，请稍后关注案件进展通知！", null, null);
            }
        } catch (Exception e) {
            log.warn("调用 MoneyAgent 失败，dispatchNo={}", dispatchNo, e);
            dispatch.setState("FAILED");
            dispatch.setLastError(e.getMessage());
            dispatch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            dispatchMapper.updateById(dispatch);
            return new MoneyBridgeResponse("资金调查任务已登记到后台排队队列，系统将自动重试并为您进行跨渠道对账调查，请耐心等待！", null, null);
        }
    }

    private MoneyBridgeResponse queryCaseProgress(String caseNo, String orderNo, Long userId) {
        AgentMoneyBindingEntity binding = null;
        if (caseNo != null && !caseNo.isBlank()) {
            binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getCaseNo, caseNo.trim())
                    .eq(AgentMoneyBindingEntity::getUserId, userId));
        } else if (orderNo != null && !orderNo.isBlank()) {
            binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getOrderNo, orderNo.trim())
                    .eq(AgentMoneyBindingEntity::getUserId, userId)
                    .orderByDesc(AgentMoneyBindingEntity::getId)
                    .last("LIMIT 1"));
        } else {
            // 找最近一个绑定
            binding = bindingMapper.selectOne(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getUserId, userId)
                    .orderByDesc(AgentMoneyBindingEntity::getId)
                    .last("LIMIT 1"));
        }

        if (binding == null) {
            return new MoneyBridgeResponse("未找到与您账号关联的资金调查案件。若有扣款异常，请提供具体订单号。", null, null);
        }

        MoneyAgentClient.MoneyCaseResponse caseResp = moneyAgentClient.getCase(binding.getCaseNo());
        String outcome = (caseResp != null && caseResp.outcome() != null) ? caseResp.outcome() : "UNDETERMINED";
        String reply = String.format("您的资金调查案件（编号：%s，关联订单：%s）最新结果：%s。",
                binding.getCaseNo(), binding.getOrderNo(), friendlyOutcome(outcome));

        return new MoneyBridgeResponse(reply, binding.getCaseNo(), binding.getTaskNo());
    }

    private String friendlyOutcome(String outcome) {
        if ("VERIFIED_NORMAL".equalsIgnoreCase(outcome)) {
            return "已核实渠道与平台记录一致，资金流向正常，未发现异常掉单或重复扣款";
        } else if ("NEEDS_REVIEW".equalsIgnoreCase(outcome)) {
            return "已检测到渠道流水与平台订单存在单边差异，调查取证材料已完成归档，正在等待财务专员人工复核确认";
        } else if ("VERIFIED_RESOLVED".equalsIgnoreCase(outcome)) {
            return "已核实外部支付/退款渠道出账成功（注：买家端实际到账时效以发卡行清算入账为准，通常需 1-3 个工作日）";
        } else if ("INCONCLUSIVE".equalsIgnoreCase(outcome)) {
            return "因部分外部证据暂未闭环或凭据正在清算中，调查结论暂未终结，系统将继续自动追踪";
        } else {
            return "系统正在全自动调查取证中，请耐心等待";
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "HASH_ERROR";
        }
    }

    public record MoneyBridgeResponse(String replyText, String caseNo, String taskNo) {}
}
