package com.example.moneyagent.api.dto;

import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 财务管理后台案件视图 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCaseAdminDto implements Serializable {

    private Long id;
    private String caseNo;
    private String scopeKey;
    private String orderNo;
    private String problemFamily;
    private String targetRef;
    private String origin;
    private String state;
    private String outcome;
    private String severity;
    private Long ownerStaffId;
    private String currentTaskNo;
    private String latestReportNo;
    private String grantRef;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;

    public static MoneyCaseAdminDto fromEntity(MoneyCaseEntity entity) {
        if (entity == null) return null;
        return MoneyCaseAdminDto.builder()
                .id(entity.getId())
                .caseNo(entity.getCaseNo())
                .scopeKey(entity.getScopeKey())
                .orderNo(entity.getOrderNo())
                .problemFamily(entity.getProblemFamily())
                .targetRef(entity.getTargetRef())
                .origin(entity.getOrigin())
                .state(entity.getState())
                .outcome(entity.getOutcome())
                .severity(entity.getSeverity())
                .ownerStaffId(entity.getOwnerStaffId())
                .currentTaskNo(entity.getCurrentTaskNo())
                .latestReportNo(entity.getLatestReportNo())
                .grantRef(entity.getGrantRef())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .closedAt(entity.getClosedAt())
                .build();
    }
}

