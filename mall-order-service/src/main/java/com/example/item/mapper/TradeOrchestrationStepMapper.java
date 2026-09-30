package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.TradeOrchestrationStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TradeOrchestrationStepMapper extends BaseMapper<TradeOrchestrationStep> {
    @Select("SELECT * FROM t_trade_orchestration_step WHERE task_no = #{taskNo} ORDER BY step_no")
    List<TradeOrchestrationStep> selectByTaskNo(@Param("taskNo") String taskNo);

    @Update("""
            UPDATE t_trade_orchestration_step
            SET status = 'RUNNING', attempt_count = attempt_count + 1, updated_time = #{now}
            WHERE id = #{id} AND status IN ('INIT', 'FAILED', 'RUNNING')
              AND (next_execute_time IS NULL OR next_execute_time <= #{now})
            """)
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_trade_orchestration_step
            SET status = 'SUCCESS', response_json = #{responseJson}, last_error = NULL,
                next_execute_time = NULL, updated_time = #{now}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markSuccess(
            @Param("id") Long id,
            @Param("responseJson") String responseJson,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE t_trade_orchestration_step
            SET status = #{status}, last_error = #{lastError},
                next_execute_time = #{nextExecuteTime}, updated_time = #{now}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("lastError") String lastError,
            @Param("nextExecuteTime") LocalDateTime nextExecuteTime,
            @Param("now") LocalDateTime now
    );
}
