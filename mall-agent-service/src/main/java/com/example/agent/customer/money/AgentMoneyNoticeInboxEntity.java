package com.example.agent.customer.money;

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
@TableName("t_agent_money_notice_inbox")
public class AgentMoneyNoticeInboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String source;
    private String eventId;
    private String payloadHash;
    private String payloadJson;
    private String state; // PENDING, PROCESSED, FAILED
    private LocalDateTime nextAttemptAt;
    private Integer attempts;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}

