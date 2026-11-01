package com.example.moneyagent.api.dto;

import com.example.moneyagent.persistence.entity.MoneyReportEntity;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 财务管理后台案件详情视图 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCaseDetailDto implements Serializable {

    private MoneyCaseAdminDto caseInfo;
    private TaskDetailDto taskInfo;
    private ReportDetailDto reportInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskDetailDto implements Serializable {
        private String taskNo;
        private String state;
        private Long version;
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;

        public static TaskDetailDto fromEntity(MoneyTaskEntity entity) {
            if (entity == null) return null;
            return TaskDetailDto.builder()
                    .taskNo(entity.getTaskNo())
                    .state(entity.getState())
                    .version(entity.getVersion())
                    .createdAt(entity.getCreatedAt())
                    .finishedAt(entity.getFinishedAt())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportDetailDto implements Serializable {
        private String reportNo;
        private String outcome;
        private String internalJson;
        private LocalDateTime createdAt;

        public static ReportDetailDto fromEntity(MoneyReportEntity entity) {
            if (entity == null) return null;
            return ReportDetailDto.builder()
                    .reportNo(entity.getReportNo())
                    .outcome(entity.getOutcome())
                    .internalJson(entity.getInternalJson())
                    .createdAt(entity.getCreatedAt())
                    .build();
        }
    }
}

