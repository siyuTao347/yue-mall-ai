package com.example.moneyagent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Component
public class MoneyTaskRecoveryJob {

    private final MoneyTaskMapper taskMapper;

    public MoneyTaskRecoveryJob(MoneyTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Scheduled(fixedDelay = 15000)
    public void recoverExpiredLeaseTasks() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        List<MoneyTaskEntity> expiredTasks = taskMapper.selectList(new LambdaQueryWrapper<MoneyTaskEntity>()
                .eq(MoneyTaskEntity::getState, "WORKING")
                .isNotNull(MoneyTaskEntity::getLeaseUntil)
                .lt(MoneyTaskEntity::getLeaseUntil, nowUtc));

        if (expiredTasks == null || expiredTasks.isEmpty()) return;

        for (MoneyTaskEntity task : expiredTasks) {
            log.warn("发现租约超时悬挂任务, taskNo={}, owner={}, leaseUntil={}",
                    task.getTaskNo(), task.getLeaseOwner(), task.getLeaseUntil());
            task.setLeaseOwner(null);
            task.setLeaseUntil(null);
            task.setState("SUBMITTED");
            task.setNextRunAt(nowUtc.plusSeconds(3));
            task.setVersion(task.getVersion() != null ? task.getVersion() + 1 : 1L);
            task.setUpdatedAt(nowUtc);
            taskMapper.updateById(task);
        }
    }
}

