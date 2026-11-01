package com.example.moneyagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.moneyagent.persistence.entity.MoneyTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface MoneyTaskMapper extends BaseMapper<MoneyTaskEntity> {

    @Select("SELECT * FROM t_money_task WHERE task_no = #{taskNo} FOR UPDATE")
    MoneyTaskEntity selectForUpdate(@Param("taskNo") String taskNo);

    @Select("SELECT * FROM t_money_task " +
            "WHERE state IN ('SUBMITTED', 'WORKING') " +
            "AND (next_run_at IS NULL OR next_run_at <= #{now}) " +
            "AND (lease_until IS NULL OR lease_until < #{now}) " +
            "ORDER BY id ASC LIMIT 1 FOR UPDATE")
    MoneyTaskEntity selectRunnableTaskForUpdate(@Param("now") LocalDateTime now);
}

