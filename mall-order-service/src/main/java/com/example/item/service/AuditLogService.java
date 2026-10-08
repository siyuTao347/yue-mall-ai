package com.example.item.service;

import api.audit.AuditLogDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.AuditLog;
import com.example.item.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 审计日志服务类
 */
@Slf4j
@Service
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 将 AuditLogDTO 转换为实体并落库
     *
     * @param logDTO 审计日志传输对象
     */
    public void saveAuditLog(AuditLogDTO logDTO) {
        if (logDTO == null) {
            return;
        }
        AuditLog entity = AuditLog.builder()
                .traceId(logDTO.getTraceId())
                .title(logDTO.getTitle())
                .businessType(logDTO.getBusinessType())
                .method(logDTO.getMethod())
                .requestMethod(logDTO.getRequestMethod())
                .operatorId(logDTO.getOperatorId())
                .operatorName(logDTO.getOperatorName())
                .operatorUrl(logDTO.getOperatorUrl())
                .operatorIp(logDTO.getOperatorIp())
                .requestParams(logDTO.getRequestParams())
                .responseResult(logDTO.getResponseResult())
                .status(logDTO.getStatus())
                .errorMsg(logDTO.getErrorMsg())
                .costTime(logDTO.getCostTime())
                .deliveryMode(logDTO.getDeliveryMode())
                .createTime(logDTO.getOperateTime() != null ? logDTO.getOperateTime() : new Date())
                .build();

        auditLogMapper.insert(entity);
        log.info("【审计日志落库成功】ID: {}, Title: {}, DeliveryMode: {}, CostTime: {}ms",
                entity.getId(), entity.getTitle(), entity.getDeliveryMode(), entity.getCostTime());
    }

    /**
     * 查询最新的 N 条审计日志（用于前端或控制台排查与验证）
     */
    public List<AuditLog> listRecentLogs(int limit) {
        return auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLog>()
                        .orderByDesc(AuditLog::getId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100)))
        );
    }
}
