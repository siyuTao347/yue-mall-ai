package com.example.moneyagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_money_case")
public class MoneyCaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String scopeKey;
    private String problemFamily;
    private String targetRef;
    private String activeDedupeKey;
    private String previousCaseNo;
    private String orderNo;
    private String origin;
    private String state;
    private String outcome;
    private String severity;
    private Long ownerStaffId;
    private String currentTaskNo;
    private String latestReportNo;
    private String grantRef;
    private Long eventSeq;
    @Version
    private Long version;
    private String closeReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
}

