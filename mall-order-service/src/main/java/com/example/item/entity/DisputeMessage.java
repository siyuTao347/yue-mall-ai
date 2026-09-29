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
@TableName("t_dispute_message")
public class DisputeMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String disputeNo;
    private String senderType;
    private Long senderId;
    private String message;
    private String attachmentUrl;
    private LocalDateTime createdTime;
}
