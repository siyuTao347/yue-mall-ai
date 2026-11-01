package com.example.moneyagent.application;

import api.agent.money.*;
import api.agent.money.contract.MoneyAdditionalInput;
import api.agent.money.contract.MoneyAgentException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.worker.MoneyTaskWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Slf4j
@Service
public class MoneyInputService {

    private final MoneyTaskMapper taskMapper;
    private final MoneyCaseMapper caseMapper;
    private final MoneyTaskInputMapper taskInputMapper;
    private final MoneyInputRequestMapper inputRequestMapper;
    private final MoneyRequestMapper requestMapper;
    private final MoneyTaskWorker taskWorker;
    private final ObjectMapper objectMapper;

    public MoneyInputService(MoneyTaskMapper taskMapper,
                             MoneyCaseMapper caseMapper,
                             MoneyTaskInputMapper taskInputMapper,
                             MoneyInputRequestMapper inputRequestMapper,
                             MoneyRequestMapper requestMapper,
                             MoneyTaskWorker taskWorker) {
        this.taskMapper = taskMapper;
        this.caseMapper = caseMapper;
        this.taskInputMapper = taskInputMapper;
        this.inputRequestMapper = inputRequestMapper;
        this.requestMapper = requestMapper;
        this.taskWorker = taskWorker;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean submitAdditionalInput(String callerService, String requestId, MoneyAdditionalInput input) {
        if (input == null || input.getTaskNo() == null || input.getTaskNo().isBlank()) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_PARAM_INVALID, "补充材料或 taskNo 不能为空");
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String safeCaller = callerService != null ? callerService : "mall-agent-service";

        // 1. 幂等检查
        if (requestId != null && !requestId.isBlank()) {
            MoneyRequestEntity req = requestMapper.selectOne(new LambdaQueryWrapper<MoneyRequestEntity>()
                    .eq(MoneyRequestEntity::getCallerId, safeCaller)
                    .eq(MoneyRequestEntity::getAction, "PROVIDE_INPUT")
                    .eq(MoneyRequestEntity::getRequestId, requestId));
            if (req != null) {
                log.info("重放补证请求回执, requestId={}", requestId);
                return true;
            }
        }

        // 2. 校验任务状态
        MoneyTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getTaskNo, input.getTaskNo().trim()));
        if (task == null) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_TASK_NOT_FOUND, "目标任务不存在: " + input.getTaskNo());
        }
        if ("COMPLETED".equals(task.getState()) || "FAILED".equals(task.getState()) || "CANCELED".equals(task.getState())) {
            throw new MoneyAgentException(MoneyErrorCodes.MONEY_FORBIDDEN, "终态任务不允许补充资料");
        }

        // 3. 校验并更新 inputRequest (若指定)
        if (input.getInputRequestNo() != null && !input.getInputRequestNo().isBlank()) {
            MoneyInputRequestEntity inputReq = inputRequestMapper.selectOne(new LambdaQueryWrapper<MoneyInputRequestEntity>()
                    .eq(MoneyInputRequestEntity::getInputNo, input.getInputRequestNo().trim()));
            if (inputReq != null) {
                if (input.getExpectedInputVersion() != null && !Objects.equals(inputReq.getInputVersion().longValue(), input.getExpectedInputVersion())) {
                    throw new MoneyAgentException(MoneyErrorCodes.MONEY_VERSION_CONFLICT, "输入版本冲突");
                }
                if (input.getAnswers() == null || input.getAnswers().isEmpty()) {
                    throw new MoneyAgentException(MoneyErrorCodes.MONEY_PARAM_INVALID, "补充材料不能为空 (answers 缺失)");
                }
                inputReq.setState("ANSWERED");
                try {
                    inputReq.setAnswerJson(objectMapper.writeValueAsString(input.getAnswers()));
                } catch (Exception ignored) {}
                inputReq.setUpdatedAt(nowUtc);
                inputReq.setVersion(inputReq.getVersion() != null ? inputReq.getVersion() + 1 : 1L);
                inputRequestMapper.updateById(inputReq);
            }
        }

        // 4. 持久化 task_input 记录
        try {
            String contentJson = objectMapper.writeValueAsString(input);
            String payloadHash = MoneySecuritySupport.computeFingerprint(contentJson);
            MoneyTaskInputEntity taskInput = MoneyTaskInputEntity.builder()
                    .taskNo(task.getTaskNo())
                    .inputSeq(1L)
                    .inputKind("ADDITIONAL_INPUT")
                    .inputRequestNo(input.getInputRequestNo())
                    .payloadHash(payloadHash)
                    .contentJson(contentJson)
                    .createdAt(nowUtc)
                    .build();
            taskInputMapper.insert(taskInput);
        } catch (Exception e) {
            log.error("写入 task_input 失败", e);
        }

        // 5. 递增任务版本与 fencingToken，将任务恢复为 SUBMITTED
        task.setState("SUBMITTED");
        task.setWaitReason(null);
        task.setNextRunAt(nowUtc);
        task.setVersion(task.getVersion() != null ? task.getVersion() + 1 : 1L);
        task.setFencingToken(task.getFencingToken() != null ? task.getFencingToken() + 1 : 1L);
        task.setUpdatedAt(nowUtc);
        taskMapper.updateById(task);

        // 6. 恢复对应 Case 状态
        MoneyCaseEntity caseEntity = caseMapper.selectOne(new LambdaQueryWrapper<MoneyCaseEntity>()
                .eq(MoneyCaseEntity::getCaseNo, task.getCaseNo()));
        if (caseEntity != null && "NEED_INPUT".equals(caseEntity.getState())) {
            caseEntity.setState("OPEN");
            caseEntity.setUpdatedAt(nowUtc);
            caseMapper.updateById(caseEntity);
        }

        // 7. 记录请求幂等
        if (requestId != null && !requestId.isBlank()) {
            try {
                MoneyRequestEntity reqEntity = MoneyRequestEntity.builder()
                        .callerId(safeCaller)
                        .action("PROVIDE_INPUT")
                        .requestId(requestId)
                        .requestHash(MoneySecuritySupport.computeFingerprint(requestId + ":" + input.getTaskNo()))
                        .taskNo(task.getTaskNo())
                        .caseNo(task.getCaseNo())
                        .state("COMPLETED")
                        .receiptJson("{\"accepted\":true}")
                        .createdAt(nowUtc)
                        .updatedAt(nowUtc)
                        .build();
                requestMapper.insert(reqEntity);
            } catch (Exception ignored) {}
        }

        // 8. 触发 Worker 执行
        taskWorker.runOneTask();

        return true;
    }
}
