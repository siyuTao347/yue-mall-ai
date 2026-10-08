package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RiskIndicator;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RiskIndicatorMapper extends BaseMapper<RiskIndicator> {
    @Insert("INSERT INTO t_risk_indicator(metric_code, subject_type, subject_id, window_type, " +
            "window_start, window_end, metric_value, updated_time) VALUES(#{metricCode}, #{subjectType}, " +
            "#{subjectId}, #{windowType}, #{windowStart}, #{windowEnd}, #{metricValue}, #{updatedTime}) " +
            "ON DUPLICATE KEY UPDATE window_start = VALUES(window_start), window_end = VALUES(window_end), " +
            "metric_value = VALUES(metric_value), updated_time = VALUES(updated_time)")
    int upsert(RiskIndicator indicator);
}
