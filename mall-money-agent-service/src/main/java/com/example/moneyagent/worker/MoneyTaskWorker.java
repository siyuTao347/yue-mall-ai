package com.example.moneyagent.worker;

import com.example.moneyagent.investigation.MoneyInvestigationGraph;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyCaseMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MoneyTaskWorker {

    private final MoneyTaskMapper taskMapper;
    private final MoneyCaseMapper caseMapper;
    private final MoneyInvestigationGraph investigationGraph;
    private final com.example.moneyagent.config.MoneyAgentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final String workerId = "WORKER_" + UUID.randomUUID().toString().substring(0, 8);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "money-task-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public MoneyTaskWorker(MoneyTaskMapper taskMapper,
                           MoneyCaseMapper caseMapper,
                           MoneyInvestigationGraph investigationGraph,
                           com.example.moneyagent.config.MoneyAgentProperties properties,
                           @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.taskMapper = taskMapper;
        this.caseMapper = caseMapper;
        this.investigationGraph = investigationGraph;
        this.properties = properties != null ? properties : new com.example.moneyagent.config.MoneyAgentProperties();
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public MoneyTaskWorker(MoneyTaskMapper taskMapper,
                           MoneyCaseMapper caseMapper,
                           MoneyInvestigationGraph investigationGraph,
                           com.example.moneyagent.config.MoneyAgentProperties properties) {
        this(taskMapper, caseMapper, investigationGraph, properties, (PlatformTransactionManager) null);
    }

    public MoneyTaskWorker(MoneyTaskMapper taskMapper,
                           MoneyCaseMapper caseMapper,
                           MoneyInvestigationGraph investigationGraph) {
        this(taskMapper, caseMapper, investigationGraph, defaultProperties(), (PlatformTransactionManager) null);
    }

    private static com.example.moneyagent.config.MoneyAgentProperties defaultProperties() {
        com.example.moneyagent.config.MoneyAgentProperties props = new com.example.moneyagent.config.MoneyAgentProperties();
        props.setWorkerEnabled(true);
        return props;
    }

    /**
     * 单次认领可运行任务，加租约锁，版本与 fencingToken 递增
     */
    @Transactional(rollbackFor = Exception.class)
    public MoneyTaskEntity claimTask() {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> doClaimTask());
        }
        return doClaimTask();
    }

    private MoneyTaskEntity doClaimTask() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()) {
            return null;
        }
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MoneyTaskEntity task = taskMapper.selectRunnableTaskForUpdate(nowUtc);
        if (task == null) {
            return null;
        }

        task.setState("WORKING");
        task.setLeaseOwner(workerId);
        int leaseSec = properties.getTaskLeaseSeconds() > 0 ? properties.getTaskLeaseSeconds() : 30;
        task.setLeaseUntil(nowUtc.plusSeconds(leaseSec));
        task.setVersion(task.getVersion() != null ? task.getVersion() + 1 : 1L);
        task.setFencingToken(task.getFencingToken() != null ? task.getFencingToken() + 1 : 1L);
        task.setUpdatedAt(nowUtc);
        taskMapper.updateById(task);
        return task;
    }

    /**
     * 执行单个任务，运行期间维持心跳自动续约租约
     */
    public boolean runOneTask() {
        MoneyTaskEntity task = claimTask();
        if (task == null) {
            return false;
        }

        ScheduledFuture<?> heartbeatFuture = null;
        int heartbeatSec = properties.getTaskHeartbeatSeconds() > 0 ? properties.getTaskHeartbeatSeconds() : 5;
        int leaseSec = properties.getTaskLeaseSeconds() > 0 ? properties.getTaskLeaseSeconds() : 30;
        final Long fencingToken = task.getFencingToken();
        final String taskNo = task.getTaskNo();

        try {
            heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    LocalDateTime renewTime = LocalDateTime.now(ZoneOffset.UTC);
                    MoneyTaskEntity renew = new MoneyTaskEntity();
                    renew.setLeaseUntil(renewTime.plusSeconds(leaseSec));
                    renew.setUpdatedAt(renewTime);
                    int updated = taskMapper.update(renew, new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MoneyTaskEntity>()
                            .eq(MoneyTaskEntity::getTaskNo, taskNo)
                            .eq(MoneyTaskEntity::getLeaseOwner, workerId)
                            .eq(MoneyTaskEntity::getFencingToken, fencingToken));
                    if (updated == 0) {
                        log.warn("心跳续约失败，租约可能已过期被抢占或 fencingToken 不匹配: taskNo={}", taskNo);
                    } else {
                        log.debug("成功续约任务租约: taskNo={}, newLeaseUntil={}", taskNo, renew.getLeaseUntil());
                    }
                } catch (Exception ex) {
                    log.warn("任务心跳续约异常: taskNo={}, error={}", taskNo, ex.getMessage());
                }
            }, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);

            MoneyCaseEntity caseEntity = caseMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MoneyCaseEntity>()
                            .eq(MoneyCaseEntity::getCaseNo, task.getCaseNo()));
            if (caseEntity != null) {
                investigationGraph.executeInvestigation(caseEntity, task);
            }
            return true;
        } catch (Exception e) {
            log.error("Worker 执行任务异常, taskNo={}", task.getTaskNo(), e);
            task.setState("FAILED");
            task.setLastErrorCode("WORKER_EXECUTION_ERROR");
            task.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(task);
            return false;
        } finally {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(true);
            }
            // 释放租约
            task.setLeaseOwner(null);
            task.setLeaseUntil(null);
            task.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(task);
        }
    }

    /**
     * 周期性扫描执行
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduleRun() {
        try {
            while (runOneTask()) {
                // 持续消化就绪队列任务
            }
        } catch (Exception e) {
            log.debug("Worker 周期调度无待运行任务或执行结束: {}", e.getMessage());
        }
    }
}

