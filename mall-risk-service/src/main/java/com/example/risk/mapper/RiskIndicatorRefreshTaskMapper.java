package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RiskIndicatorRefreshTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RiskIndicatorRefreshTaskMapper extends BaseMapper<RiskIndicatorRefreshTask> {
    @Select("SELECT * FROM t_risk_indicator_refresh_task WHERE status IN ('PENDING', 'FAILED') " +
            "AND next_execute_time <= #{now} ORDER BY next_execute_time, id LIMIT #{limit}")
    List<RiskIndicatorRefreshTask> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Insert("INSERT INTO t_risk_indicator_refresh_task(subject_type, subject_id, metric_codes_json, status, " +
            "retry_count, max_retry_count, next_execute_time, created_time, updated_time) " +
            "VALUES(#{subjectType}, #{subjectId}, #{metricCodesJson}, 'PENDING', 0, 3, #{nextExecuteTime}, " +
            "#{nextExecuteTime}, #{nextExecuteTime}) ON DUPLICATE KEY UPDATE " +
            "next_execute_time = LEAST(next_execute_time, VALUES(next_execute_time)), retry_count = 0, " +
            "last_error = NULL, updated_time = VALUES(updated_time)")
    int enqueue(@Param("subjectType") String subjectType, @Param("subjectId") Long subjectId,
                @Param("metricCodesJson") String metricCodesJson,
                @Param("nextExecuteTime") LocalDateTime nextExecuteTime);

    @Update("UPDATE t_risk_indicator_refresh_task SET status = 'RUNNING', updated_time = #{now} " +
            "WHERE id = #{id} AND status IN ('PENDING', 'FAILED')")
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM t_risk_indicator_refresh_task WHERE subject_type = #{subjectType} " +
            "AND subject_id = #{subjectId} AND status = #{status} AND id <> #{id}")
    int clearOldStatus(@Param("subjectType") String subjectType, @Param("subjectId") Long subjectId,
                       @Param("status") String status, @Param("id") Long id);

    @Update("UPDATE t_risk_indicator_refresh_task SET status = 'SUCCESS', last_error = NULL, " +
            "updated_time = #{now} WHERE id = #{id} AND status = 'RUNNING'")
    int markSuccess(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_indicator_refresh_task SET status = #{status}, retry_count = #{retryCount}, " +
            "next_execute_time = #{nextExecuteTime}, last_error = #{lastError}, updated_time = #{now} " +
            "WHERE id = #{id} AND status = 'RUNNING'")
    int markResult(@Param("id") Long id, @Param("status") String status,
                   @Param("retryCount") int retryCount, @Param("nextExecuteTime") LocalDateTime nextExecuteTime,
                   @Param("lastError") String lastError, @Param("now") LocalDateTime now);
}
