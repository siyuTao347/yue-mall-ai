package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MockFaultPlanMapper extends BaseMapper<MockFaultPlan> {

    @Select("SELECT * FROM t_mock_fault_plan WHERE scope_id = #{scopeId} " +
            "AND scenario_key = #{scenarioKey} AND enabled = 1 " +
            "AND (target_attempt_no = #{attemptNo} OR (target_attempt_no IS NULL AND (target_date IS NULL OR target_date = #{targetDate}))) " +
            "ORDER BY plan_version DESC LIMIT 1")
    MockFaultPlan findActivePlan(@Param("scopeId") String scopeId,
                                 @Param("scenarioKey") String scenarioKey,
                                 @Param("attemptNo") String attemptNo,
                                 @Param("targetDate") LocalDate targetDate);

    @Select("SELECT * FROM t_mock_fault_plan WHERE scope_id = #{scopeId} ORDER BY id DESC")
    List<MockFaultPlan> listByScope(@Param("scopeId") String scopeId);
}
