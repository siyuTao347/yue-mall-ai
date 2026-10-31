package com.example.item.money;

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
@TableName("t_money_channel_observation")
public class MoneyChannelObservationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String observationNo;
    private String scopeKey;
    private String attemptNo;
    private String refundNo;
    private String profileId;
    private Integer profileVersion;
    private String requestId;
    private String observationJson;
    private String resultHash;
    private LocalDateTime observedAt;
    private LocalDateTime createdAt;
}

