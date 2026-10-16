package com.example.item.payment.mock;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_mock_fault_plan")
public class MockFaultPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeId;
    private String scenarioKey;
    private Integer planVersion;
    private String targetAttemptNo;
    private LocalDate targetDate;
    private String paramsJson;
    private Long seed;
    private Integer enabled;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
