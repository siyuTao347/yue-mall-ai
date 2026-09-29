package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_sensitive_word")
public class SensitiveWord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String wordCode;
    private String category;
    private String word;
    private Boolean enabled;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
