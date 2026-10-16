package com.example.item.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_settlement_calendar_day")
public class SettlementCalendarDay {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String calendarId;
    private LocalDate calDate;
    private Integer isSettlementDay;
    private Integer version;
    private String reason;
    private LocalDateTime createdTime;
}
