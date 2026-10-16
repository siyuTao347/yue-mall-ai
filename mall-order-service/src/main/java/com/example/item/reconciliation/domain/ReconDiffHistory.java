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
@TableName("t_recon_diff_history")
public class ReconDiffHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String diffNo;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String operator;
    private String evidence;
    private String commandNo;
    private LocalDateTime createdTime;
}
