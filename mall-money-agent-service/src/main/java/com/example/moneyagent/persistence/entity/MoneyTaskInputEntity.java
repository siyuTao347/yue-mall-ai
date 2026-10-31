package com.example.moneyagent.persistence.entity;

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
@TableName("t_money_task_input")
public class MoneyTaskInputEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private Long inputSeq;
    private Long requestRowId;
    private String inputKind;
    private String inputRequestNo;
    private String grantNo;
    private String payloadHash;
    private String contentJson;
    private LocalDateTime createdAt;
}

