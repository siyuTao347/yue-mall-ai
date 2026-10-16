package com.example.item.payment.settlement;

import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.domain.SettlementCalendarDay;
import com.example.item.payment.mapper.SettlementCalendarDayMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
public class SettlementScheduleService {

    private final SettlementCalendarDayMapper calendarMapper;

    public SettlementScheduleService(SettlementCalendarDayMapper calendarMapper) {
        this.calendarMapper = calendarMapper;
    }

    public LocalDateTime calculateSettlementTime(ChannelProfileSnapshot profile, LocalDate businessDate) {
        String basis = profile.settlementDayBasis();
        LocalTime time = profile.settlementTime() != null ? LocalTime.parse(profile.settlementTime()) : LocalTime.of(9, 30);

        if ("REALTIME".equalsIgnoreCase(basis)) {
            return LocalDateTime.of(businessDate, LocalTime.now());
        }

        if ("CALENDAR".equalsIgnoreCase(basis)) {
            LocalDate targetDate = businessDate.plusDays(profile.settlementOffsetDays());
            return LocalDateTime.of(targetDate, time);
        }

        if ("BUSINESS".equalsIgnoreCase(basis)) {
            String calendarId = profile.settlementCalendarId() != null ? profile.settlementCalendarId() : "MOCK_CN_ACQUIRER";
            LocalDate current = businessDate;
            int daysNeeded = profile.settlementOffsetDays();
            int daysCounted = 0;

            // 循环寻找后续的有效结算工作日
            while (daysCounted < daysNeeded) {
                current = current.plusDays(1);
                if (isSettlementWorkday(calendarId, current)) {
                    daysCounted++;
                }
            }
            return LocalDateTime.of(current, time);
        }

        return LocalDateTime.of(businessDate.plusDays(1), time);
    }

    public boolean isSettlementWorkday(String calendarId, LocalDate date) {
        if (calendarMapper != null) {
            SettlementCalendarDay day = calendarMapper.selectDay(calendarId, date);
            if (day != null) {
                return day.getIsSettlementDay() != null && day.getIsSettlementDay() == 1;
            }
        }
        // 若日历表中未配置，默认周一到周五为结算日
        java.time.DayOfWeek dow = date.getDayOfWeek();
        return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY;
    }
}
