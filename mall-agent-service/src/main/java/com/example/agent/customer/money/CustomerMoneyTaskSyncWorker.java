package com.example.agent.customer.money;

import api.agent.money.MoneyIdGenerator;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.MoneyCallerCredential;
import api.agent.money.contract.MoneyCaseReference;
import api.agent.money.contract.MoneyCaseView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 客服端任务与案件状态同步 Worker
 * 定期同步非终态任务与活动案件，按 CAS 更新最新受众投影，在发生关键事实变化时触发投递
 */
@Slf4j
@Component
public class CustomerMoneyTaskSyncWorker {

    private final AgentMoneyBindingMapper bindingMapper;
    private final MoneyAgentClient moneyAgentClient;
    private final CustomerMoneyDeliveryService deliveryService;
    private final CustomerMoneyAnswerAssembler answerAssembler;

    public CustomerMoneyTaskSyncWorker(AgentMoneyBindingMapper bindingMapper,
                                       MoneyAgentClient moneyAgentClient,
                                       CustomerMoneyDeliveryService deliveryService,
                                       CustomerMoneyAnswerAssembler answerAssembler) {
        this.bindingMapper = bindingMapper;
        this.moneyAgentClient = moneyAgentClient;
        this.deliveryService = deliveryService;
        this.answerAssembler = answerAssembler;
    }

    public CustomerMoneyTaskSyncWorker(AgentMoneyBindingMapper bindingMapper,
                                       MoneyAgentClient moneyAgentClient) {
        this(bindingMapper, moneyAgentClient, null, new CustomerMoneyAnswerAssembler());
    }

    @Scheduled(fixedDelay = 5000)
    public void syncActiveCases() {
        int maxRounds = 5;
        int round = 0;
        while (round++ < maxRounds) {
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

            // 扫描到达轮询周期的活动案件（OPEN, NEED_INPUT, NEED_REVIEW, WAITING_BIZ）
            // 依据 next_poll_at IS NULL OR next_poll_at <= nowUtc 筛选，按 next_poll_at 升序，杜绝饥饿
            List<AgentMoneyBindingEntity> activeBindings = bindingMapper.selectList(new LambdaQueryWrapper<AgentMoneyBindingEntity>()
                    .eq(AgentMoneyBindingEntity::getSubscriptionState, "ACTIVE")
                    .in(AgentMoneyBindingEntity::getCaseState, List.of("OPEN", "NEED_INPUT", "NEED_REVIEW", "WAITING_BIZ"))
                    .and(w -> w.isNull(AgentMoneyBindingEntity::getNextPollAt).or().le(AgentMoneyBindingEntity::getNextPollAt, nowUtc))
                    .orderByAsc(AgentMoneyBindingEntity::getNextPollAt)
                    .orderByAsc(AgentMoneyBindingEntity::getId)
                    .last("LIMIT 20"));

            if (activeBindings == null || activeBindings.isEmpty()) {
                break;
            }

            for (AgentMoneyBindingEntity binding : activeBindings) {
                try {
                    syncOneBinding(binding);
                } catch (Exception e) {
                    log.warn("同步案件状态失败, caseNo={}, userId={}", binding.getCaseNo(), binding.getUserId(), e);
                }
            }

            if (activeBindings.size() < 20) {
                break;
            }
        }
    }

    public boolean syncOneBinding(AgentMoneyBindingEntity binding) {
        if (binding == null || !"ACTIVE".equalsIgnoreCase(binding.getSubscriptionState())) {
            return false;
        }

        Long uid = binding.getUserId() != null ? binding.getUserId() : 0L;
        String scopeKey = "ORDER:" + binding.getOrderNo();
        MoneyCallerCredential credential = buildCredential(uid, scopeKey, binding.getGrantRef());

        MoneyCaseView caseView = null;
        try {
            caseView = moneyAgentClient.getCase(MoneyCaseReference.of(binding.getCaseNo()), credential);
        } catch (UnsupportedOperationException | AbstractMethodError e) {
            MoneyAgentClient.MoneyCaseResponse resp = moneyAgentClient.getCase(binding.getCaseNo());
            if (resp != null) {
                caseView = MoneyCaseView.builder()
                        .caseNo(resp.caseNo())
                        .caseState(resp.caseState())
                        .resolutionOutcome(resp.outcome())
                        .projectionRevision(binding.getProjectionRevision() != null ? binding.getProjectionRevision() + 1 : 1L)
                        .build();
            }
        } catch (Exception ex) {
            log.warn("RPC 获取案件状态异常, caseNo={}, userId={}: {}", binding.getCaseNo(), binding.getUserId(), ex.getMessage());
            // 异常退避：推迟 15 秒后再轮询，防止错误死循环霸占队列
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            binding.setNextPollAt(nowUtc.plusSeconds(15));
            binding.setUpdatedAt(nowUtc);
            bindingMapper.updateById(binding);
            throw ex;
        }

        if (caseView == null) {
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            binding.setNextPollAt(nowUtc.plusSeconds(15));
            binding.setUpdatedAt(nowUtc);
            bindingMapper.updateById(binding);
            return false;
        }

        Long oldRevision = binding.getProjectionRevision() != null ? binding.getProjectionRevision() : 0L;
        Long newRevision = caseView.getProjectionRevision() != null ? caseView.getProjectionRevision() : oldRevision + 1;

        String oldCaseState = binding.getCaseState();
        String oldTaskState = binding.getTaskState();
        String newCaseState = caseView.getCaseState() != null ? caseView.getCaseState() : oldCaseState;
        String newTaskState = caseView.getTaskState() != null ? caseView.getTaskState() : oldTaskState;

        boolean revisionChanged = newRevision > oldRevision;
        boolean stateChanged = !newCaseState.equalsIgnoreCase(oldCaseState) || (newTaskState != null && !newTaskState.equalsIgnoreCase(oldTaskState));

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime nextPollAt = calculateNextPollAt(newCaseState, nowUtc);

        if (!revisionChanged && !stateChanged) {
            // 无版本或状态变化，推进轮询时间，避免同批长耗时案件死锁霸占 LIMIT 20 导致饥饿
            binding.setNextPollAt(nextPollAt);
            binding.setUpdatedAt(nowUtc);
            bindingMapper.updateById(binding);
            return false;
        }

        binding.setCaseState(newCaseState);
        if (newTaskState != null) {
            binding.setTaskState(newTaskState);
        }
        if (caseView.getCurrentTaskNo() != null && !caseView.getCurrentTaskNo().isBlank()) {
            binding.setTaskNo(caseView.getCurrentTaskNo());
        }
        binding.setProjectionRevision(newRevision);
        binding.setNextPollAt(nextPollAt);
        binding.setUpdatedAt(nowUtc);

        int updated = bindingMapper.updateById(binding);
        if (updated > 0 && deliveryService != null && stateChanged) {
            // 有关键状态变化时，触发消息/卡片投递
            String notice = answerAssembler.assembleCaseView(binding.getOrderNo(), caseView);
            String sessionNo = binding.getCurrentSessionNo() != null ? binding.getCurrentSessionNo() : binding.getOriginSessionNo();
            deliveryService.deliverProjectionUpdate(binding.getCaseNo(), newRevision, uid, sessionNo, "CASE_UPDATED", notice, null, null);
        }

        return updated > 0;
    }

    private LocalDateTime calculateNextPollAt(String caseState, LocalDateTime nowUtc) {
        if (caseState == null) {
            return nowUtc.plusSeconds(10);
        }
        String s = caseState.toUpperCase();
        if ("RESOLVED".equals(s) || "CLOSED".equals(s) || "CANCELED".equals(s)) {
            return null; // 终态停止轮询
        } else if ("NEED_INPUT".equals(s)) {
            return nowUtc.plusSeconds(30); // 等待输入时低频检查 (30s)
        } else if ("NEED_REVIEW".equals(s) || "WAITING_BIZ".equals(s)) {
            return nowUtc.plusSeconds(60); // 等待财务/业务回执时降低频率 (60s)
        } else {
            return nowUtc.plusSeconds(10); // 普通活动调查 (10s)
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
