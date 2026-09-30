package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.TradeOrchestrationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TradeOrchestrationTaskMapper extends BaseMapper<TradeOrchestrationTask> {
    String RECOVERABLE_CONDITION = """
            ((status IN ('INIT', 'FAILED', 'COMPENSATING')
              AND (next_execute_time IS NULL OR next_execute_time <= #{now}))
            OR (status = 'RUNNING' AND (locked_until IS NULL OR locked_until < #{now})))
            """;

    @Select("SELECT * FROM t_trade_orchestration_task WHERE task_no = #{taskNo}")
    TradeOrchestrationTask selectByTaskNo(@Param("taskNo") String taskNo);

    @Select("SELECT * FROM t_trade_orchestration_task WHERE " + RECOVERABLE_CONDITION
            + " ORDER BY id LIMIT #{limit}")
    List<TradeOrchestrationTask> selectRecoverable(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE t_trade_orchestration_task
            SET status = 'RUNNING', locked_by = #{lockedBy}, locked_until = #{lockedUntil},
                updated_time = #{now}
            WHERE id = #{id} AND (
              (status IN ('INIT', 'FAILED', 'COMPENSATING')
               AND (next_execute_time IS NULL OR next_execute_time <= #{now}))
              OR (status = 'RUNNING' AND (locked_until IS NULL OR locked_until < #{now}))
            )
            """)
    int tryClaim(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE t_trade_orchestration_task
            SET status = #{status}, retry_count = retry_count + 1,
                next_execute_time = #{nextExecuteTime}, locked_by = NULL, locked_until = NULL,
                updated_time = #{now}
            WHERE task_no = #{taskNo}
            """)
    int markFailed(
            @Param("taskNo") String taskNo,
            @Param("status") String status,
            @Param("nextExecuteTime") LocalDateTime nextExecuteTime,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE t_trade_orchestration_task
            SET status = #{status}, next_execute_time = #{nextExecuteTime},
                locked_by = NULL, locked_until = NULL, updated_time = #{now}
            WHERE task_no = #{taskNo} AND status = 'RUNNING'
            """)
    int releaseWaiting(
            @Param("taskNo") String taskNo,
            @Param("status") String status,
            @Param("nextExecuteTime") LocalDateTime nextExecuteTime,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE t_trade_orchestration_task
            SET status = #{status}, locked_by = NULL, locked_until = NULL,
                next_execute_time = NULL, updated_time = #{now}
            WHERE task_no = #{taskNo} AND status = 'RUNNING'
            """)
    int finish(
            @Param("taskNo") String taskNo,
            @Param("status") String status,
            @Param("now") LocalDateTime now
    );
}
