package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_delivery_evidence")
public class DeliveryEvidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String evidenceType;
    private String fileUrl;
    private String content;
    private String fileHash;
    private String uploaderType;
    private Long uploaderId;
    private LocalDateTime createdTime;
}
