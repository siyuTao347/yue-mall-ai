package com.example.moneyagent.application;

import api.agent.money.MoneyErrorCodes;
import api.agent.money.MoneySecuritySupport;
import api.agent.money.contract.MoneyAgentException;
import api.agent.money.contract.MoneyCancelReceipt;
import api.agent.money.contract.MoneyCancelRequest;
import api.agent.money.contract.MoneyTaskView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.persistence.entity.MoneyRequestEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskAccessEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyRequestMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskAccessMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class MoneyTaskService {

    private final MoneyTaskMapper taskMapper;
    private final MoneyCaseMapper caseMapper;
    private final MoneyTaskAccessMapper taskAccessMapper;
    private final MoneyRequestMapper requestMapper;

    public MoneyTaskService(MoneyTaskMapper taskMapper,
                            MoneyCaseMapper caseMapper,
                            MoneyTaskAccessMapper taskAccessMapper,
                            MoneyRequestMapper requestMapper) {
        this.taskMapper = taskMapper;
        this.caseMapper = caseMapper;
        this.taskAccessMapper = taskAccessMapper;
        this.requestMapper = requestMapper;
    }

    public MoneyTaskView getTaskView(String taskNo) {
        if (taskNo == null || taskNo.isBlank()) return null;
        MoneyTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getTaskNo, taskNo.trim()));
        if (task == null) return null;

        return MoneyTaskView.builder()
                .taskNo(task.getTaskNo())
                .caseNo(task.getCaseNo())
                .contextId(task.getContextId())
                .taskState(task.getState())
                .taskVersion(task.getVersion())
                .updatedAt(task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public MoneyCancelReceipt cancelTask(String callerService, String requestId, MoneyCancelRequest request) {
        if (request == null || request.getTaskNo() == null || request.getTaskNo().isBlank()) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_PARAM_INVALID, "取消请求参数不能为空");
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String safeCaller = callerService != null ? callerService : "mall-agent-service";

        // 1. 幂等检查
        if (requestId != null && !requestId.isBlank()) {
            MoneyRequestEntity req = requestMapper.selectOne(new LambdaQueryWrapper<MoneyRequestEntity>()
                    .eq(MoneyRequestEntity::getCallerId, safeCaller)
                    .eq(MoneyRequestEntity::getAction, "CANCEL_TASK")
                    .eq(MoneyRequestEntity::getRequestId, requestId));
            if (req != null) {
                return MoneyCancelReceipt.builder()
                        .canceled(true)
                        .taskNo(request.getTaskNo())
                        .taskState("CANCELED")
                        .message("重放任务取消回执")
                        .build();
            }
        }

        // 2. 检查任务存在性
        MoneyTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getTaskNo, request.getTaskNo().trim()));
        if (task == null) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_TASK_NOT_FOUND, "目标任务不存在: " + request.getTaskNo());
        }

        // 已终态直接返回
        if ("COMPLETED".equals(task.getState()) || "FAILED".equals(task.getState()) || "CANCELED".equals(task.getState())) {
            return MoneyCancelReceipt.builder()
                    .canceled(true)
                    .taskNo(task.getTaskNo())
                    .taskState(task.getState())
                    .message("任务已处于终态: " + task.getState())
                    .build();
        }

        // 3. 校验 expectedTaskVersion
        if (request.getExpectedTaskVersion() != null && !Objects.equals(task.getVersion(), request.getExpectedTaskVersion())) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_VERSION_CONFLICT,
                    "任务版本冲突，当前版本: " + task.getVersion() + ", 预期版本: " + request.getExpectedTaskVersion());
        }

        // 4. 检查共享订阅关系 (t_money_task_access)
        List<MoneyTaskAccessEntity> accessList = taskAccessMapper.selectList(new LambdaQueryWrapper<MoneyTaskAccessEntity>()
                .eq(MoneyTaskAccessEntity::getTaskNo, task.getTaskNo()));

        boolean hasOtherSubscribers = false;
        if (accessList != null && accessList.size() > 1) {
            hasOtherSubscribers = true;
            for (MoneyTaskAccessEntity acc : accessList) {
                if (safeCaller.equalsIgnoreCase(acc.getCallerId())) {
                    acc.setAudienceScope("MUTED");
                    acc.setUpdatedAt(nowUtc);
                    taskAccessMapper.updateById(acc);
                }
            }
        }

        if (!hasOtherSubscribers) {
            // 唯一订阅方或全权取消：将 task 状态更新为 CANCELED，但不关闭整个 case
            task.setState("CANCELED");
            task.setWaitReason(null);
            task.setLastErrorCode(request.getReason() != null ? request.getReason() : "USER_CANCELLED");
            task.setVersion(task.getVersion() != null ? task.getVersion() + 1 : 1L);
            task.setFinishedAt(nowUtc);
            task.setUpdatedAt(nowUtc);
            taskMapper.updateById(task);
            log.info("任务已独立取消, taskNo={}, reason={}", task.getTaskNo(), request.getReason());
        } else {
            log.info("存在其他共享订阅者，仅静音调用方订阅, taskNo={}, caller={}", task.getTaskNo(), safeCaller);
        }

        // 记录请求幂等
        if (requestId != null && !requestId.isBlank()) {
            try {
                MoneyRequestEntity reqEntity = MoneyRequestEntity.builder()
                        .callerId(safeCaller)
                        .action("CANCEL_TASK")
                        .requestId(requestId)
                        .requestHash(MoneySecuritySupport.computeFingerprint(requestId + ":" + task.getTaskNo()))
                        .taskNo(task.getTaskNo())
                        .caseNo(task.getCaseNo())
                        .state("COMPLETED")
                        .receiptJson("{\"canceled\":true,\"taskNo\":\"" + task.getTaskNo() + "\"}")
                        .createdAt(nowUtc)
                        .updatedAt(nowUtc)
                        .build();
                requestMapper.insert(reqEntity);
            } catch (Exception ignored) {}
        }

        return MoneyCancelReceipt.builder()
                .canceled(true)
                .taskNo(task.getTaskNo())
                .taskState(task.getState())
                .message(hasOtherSubscribers ? "已静音当前客户端订阅，后台财务调查继续" : "任务已成功取消")
                .build();
    }
}
