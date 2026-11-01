package com.example.moneyagent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.persistence.entity.MoneyGrantEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskAccessEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import com.example.moneyagent.persistence.mapper.MoneyGrantMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskAccessMapper;
import com.example.moneyagent.persistence.mapper.MoneyTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资金授权 (Grant) 与任务访问权限 (Task Access) 管理服务
 * <p>
 * 负责受理事务中的授权注册、任务访问绑定、执行前/发布前授权核验以及受控撤回 (Revoke)。
 * </p>
 */
@Slf4j
@Service
public class MoneyGrantService {

    private final MoneyGrantMapper grantMapper;
    private final MoneyTaskAccessMapper taskAccessMapper;
    private final MoneyTaskMapper taskMapper;
    private final Map<String, MoneyGrantEntity> localGrantCache = new ConcurrentHashMap<>();

    public MoneyGrantService(MoneyGrantMapper grantMapper,
                             MoneyTaskAccessMapper taskAccessMapper,
                             MoneyTaskMapper taskMapper) {
        this.grantMapper = grantMapper;
        this.taskAccessMapper = taskAccessMapper;
        this.taskMapper = taskMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public MoneyGrantEntity registerGrant(String grantNo, String issuer, String subjectType, String subjectId,
                                          String scopeJson, String allowedActionsJson, String allowedReceiversJson,
                                          LocalDateTime expiresAt) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MoneyGrantEntity existing = getGrant(grantNo);

        if (existing != null) {
            log.info("命中已有 Grant, grantNo={}, state={}", grantNo, existing.getState());
            return existing;
        }

        MoneyGrantEntity grant = MoneyGrantEntity.builder()
                .grantNo(grantNo)
                .issuer(issuer != null ? issuer : "mall-agent-service")
                .subjectType(subjectType != null ? subjectType : "BUYER")
                .subjectId(subjectId != null ? subjectId : "0")
                .scopeJson(scopeJson != null ? scopeJson : "{}")
                .allowedActionsJson(allowedActionsJson != null ? allowedActionsJson : "[\"*\"]")
                .allowedReceiversJson(allowedReceiversJson != null ? allowedReceiversJson : "[\"mall-money-agent-service\"]")
                .policyVersion("V1")
                .state("ACTIVE")
                .expiresAt(expiresAt != null ? expiresAt : nowUtc.plusDays(1))
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .version(1L)
                .build();

        try {
            grantMapper.insert(grant);
        } catch (Exception e) {
            log.warn("插入 t_money_grant 失败或已存在: {}", e.getMessage());
        }
        localGrantCache.put(grantNo, grant);

        log.info("成功注册资金授权 Grant, grantNo={}, subjectId={}:{}", grantNo, subjectType, subjectId);
        return grant;
    }

    @Transactional(rollbackFor = Exception.class)
    public MoneyTaskAccessEntity createTaskAccess(String taskNo, String grantNo, String callerId,
                                                  String audienceScope, LocalDateTime expiresAt) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MoneyTaskAccessEntity existing = null;
        try {
            existing = taskAccessMapper.selectOne(new LambdaQueryWrapper<MoneyTaskAccessEntity>()
                    .eq(MoneyTaskAccessEntity::getTaskNo, taskNo)
                    .eq(MoneyTaskAccessEntity::getGrantNo, grantNo)
                    .eq(MoneyTaskAccessEntity::getAudienceScope, audienceScope));
        } catch (Exception ignored) {}

        if (existing != null) {
            return existing;
        }

        MoneyTaskAccessEntity access = MoneyTaskAccessEntity.builder()
                .taskNo(taskNo)
                .grantNo(grantNo)
                .callerId(callerId != null ? callerId : "mall-agent-service")
                .audienceScope(audienceScope != null ? audienceScope : "BUYER")
                .state("ACTIVE")
                .expiresAt(expiresAt != null ? expiresAt : nowUtc.plusDays(1))
                .createdAt(nowUtc)
                .updatedAt(nowUtc)
                .version(1L)
                .build();

        try {
            taskAccessMapper.insert(access);
        } catch (Exception e) {
            log.warn("插入 t_money_task_access 异常: {}", e.getMessage());
        }
        log.info("成功建立 Task Access 绑定, taskNo={}, grantNo={}, audienceScope={}", taskNo, grantNo, audienceScope);
        return access;
    }

    public boolean isGrantActive(String grantNo) {
        if (grantNo == null || grantNo.isBlank()) return false;
        MoneyGrantEntity grant = getGrant(grantNo);
        if (grant == null) return false;
        if (!"ACTIVE".equalsIgnoreCase(grant.getState())) return false;
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        return grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(nowUtc);
    }

    public MoneyGrantEntity getGrant(String grantNo) {
        if (grantNo == null || grantNo.isBlank()) return null;
        String key = grantNo.trim();
        MoneyGrantEntity cached = localGrantCache.get(key);
        if (cached != null) {
            return cached;
        }

        try {
            MoneyGrantEntity dbEntity = grantMapper.selectOne(new LambdaQueryWrapper<MoneyGrantEntity>()
                    .eq(MoneyGrantEntity::getGrantNo, key));
            if (dbEntity != null) {
                localGrantCache.put(key, dbEntity);
                return dbEntity;
            }
        } catch (Exception ignored) {}

        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public GrantRevocationResult revokeGrant(String grantNo, String reason, Long expectedVersion) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MoneyGrantEntity grant = getGrant(grantNo);

        if (grant == null) {
            return new GrantRevocationResult(grantNo, false, 0, "Grant不存在: " + grantNo);
        }

        if ("REVOKED".equalsIgnoreCase(grant.getState())) {
            return new GrantRevocationResult(grantNo, true, 0, "Grant此前已被撤回");
        }

        if (expectedVersion != null && !expectedVersion.equals(grant.getVersion())) {
            return new GrantRevocationResult(grantNo, false, 0, "Grant版本冲突: expected=" + expectedVersion + ", actual=" + grant.getVersion());
        }

        grant.setState("REVOKED");
        grant.setRevokedAt(nowUtc);
        grant.setUpdatedAt(nowUtc);
        grant.setVersion(grant.getVersion() + 1);

        try {
            grantMapper.updateById(grant);
        } catch (Exception ignored) {}
        localGrantCache.put(grantNo, grant);

        // 分批使关联的在途任务失效，状态转为 AUTH_REQUIRED，清除租约
        int affectedCount = 0;
        try {
            List<MoneyTaskEntity> activeTasks = taskMapper.selectList(new LambdaQueryWrapper<MoneyTaskEntity>()
                    .eq(MoneyTaskEntity::getExecutionGrantRef, grantNo)
                    .in(MoneyTaskEntity::getState, List.of("SUBMITTED", "WORKING")));

            if (activeTasks != null) {
                for (MoneyTaskEntity task : activeTasks) {
                    task.setState("AUTH_REQUIRED");
                    task.setWaitReason("GRANT_REVOKED");
                    task.setLeaseOwner(null);
                    task.setLeaseUntil(null);
                    task.setUpdatedAt(nowUtc);
                    task.setVersion(task.getVersion() + 1);
                    taskMapper.updateById(task);
                    affectedCount++;
                }
            }
        } catch (Exception e) {
            log.warn("分批失效任务异常: {}", e.getMessage());
        }

        log.warn("成功撤回资金授权 Grant, grantNo={}, affectedTasks={}", grantNo, affectedCount);
        return new GrantRevocationResult(grantNo, true, affectedCount, "撤回成功");
    }

    public boolean validateTaskAccess(String taskNo, String grantNo, String callerId, String audienceScope) {
        if (taskNo == null || grantNo == null) return false;
        try {
            MoneyTaskAccessEntity access = taskAccessMapper.selectOne(new LambdaQueryWrapper<MoneyTaskAccessEntity>()
                    .eq(MoneyTaskAccessEntity::getTaskNo, taskNo)
                    .eq(MoneyTaskAccessEntity::getGrantNo, grantNo)
                    .eq(MoneyTaskAccessEntity::getAudienceScope, audienceScope)
                    .eq(MoneyTaskAccessEntity::getState, "ACTIVE"));
            if (access == null) return false;
            return access.getExpiresAt() == null || access.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
        } catch (Exception e) {
            log.warn("验证 TaskAccess 异常: {}", e.getMessage());
            return false;
        }
    }

    public record GrantRevocationResult(String grantNo, boolean success, int affectedTasks, String message) {}
}
