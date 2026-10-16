package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.SettlementCalendarDay;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SettlementCalendarDayMapper extends BaseMapper<SettlementCalendarDay> {

    @Select("SELECT * FROM t_settlement_calendar_day WHERE calendar_id = #{calendarId} " +
            "AND cal_date = #{calDate} ORDER BY version DESC LIMIT 1")
    SettlementCalendarDay selectDay(@Param("calendarId") String calendarId, @Param("calDate") LocalDate calDate);

    @Select("SELECT * FROM t_settlement_calendar_day WHERE calendar_id = #{calendarId} " +
            "AND cal_date >= #{startDate} AND cal_date <= #{endDate} ORDER BY cal_date ASC")
    List<SettlementCalendarDay> listDays(@Param("calendarId") String calendarId,
                                         @Param("startDate") LocalDate startDate,
                                         @Param("endDate") LocalDate endDate);
}
