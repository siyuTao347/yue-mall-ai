package com.example.item.payment.domain;

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
@TableName("t_payment_event_inbox")
public class PaymentEventInbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String profileId;
    private String channelMerchantNo;
    private String attemptNo;
    private String refundNo;
    private String channelTradeNo;
    private String eventType;
    private String verifiedPayload;
    private String source;
    private String processingStatus;
    private String failureReason;
    private LocalDateTime nextExecuteAt;
    private Long version;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
