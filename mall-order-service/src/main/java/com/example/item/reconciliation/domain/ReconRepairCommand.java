package com.example.item.reconciliation.domain;

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
@TableName("t_recon_repair_command")
public class ReconRepairCommand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String commandNo;
    private String diffNo;
    private String commandType;
    private String requestKey;
    private String payloadJson;
    private String status;
    private String resultJson;
    private String operator;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
