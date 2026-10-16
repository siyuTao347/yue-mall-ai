package com.example.item.payment.mock;

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
@TableName("t_mock_notification_outbox")
public class MockNotificationOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String profileId;
    private String channelMerchantNo;
    private String eventType;
    private String payloadJson;
    private String signature;
    private Integer retryCount;
    private LocalDateTime nextSendAt;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
