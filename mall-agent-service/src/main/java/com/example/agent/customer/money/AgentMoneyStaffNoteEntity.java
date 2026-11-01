package com.example.agent.customer.money;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_agent_money_staff_note")
public class AgentMoneyStaffNoteEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private Long projectionRevision;
    private String sessionNo;
    private Long assignedStaffId;
    private String publicForStaffJson;
    private Long basedOnControlEpoch;
    private LocalDateTime createdAt;
}

