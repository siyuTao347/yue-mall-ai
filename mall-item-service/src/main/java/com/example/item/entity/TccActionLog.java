package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_tcc_action_log")
public class TccActionLog {
    private String txId;
    private String branchId;
    private String actionName;
    private Integer status; // 0-Try中, 1-Try成功, 2-Confirm成功, 3-Cancel成功, 4-防悬挂记录
}
