package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconTaskMapper extends BaseMapper<ReconTask> {

    @Select("SELECT * FROM t_recon_task WHERE task_no = #{taskNo}")
    ReconTask selectByTaskNo(@Param("taskNo") String taskNo);

    @Select("SELECT * FROM t_recon_task WHERE task_status IN ('INIT', 'RUNNING') " +
            "AND window_end <= #{now} AND (locked_until IS NULL OR locked_until <= #{now}) LIMIT #{limit}")
    List<ReconTask> selectExecutableTasks(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_recon_task SET task_status = 'RUNNING', lease_token = #{leaseToken}, " +
            "locked_until = #{lockedUntil}, updated_time = #{now} " +
            "WHERE id = #{id} AND (locked_until IS NULL OR locked_until <= #{now})")
    int tryClaimTask(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                     @Param("lockedUntil") LocalDateTime lockedUntil, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM t_recon_task WHERE profile_id = #{profileId} AND business_date = #{date} " +
            "AND statement_type = #{type} LIMIT 1")
    ReconTask findTask(@Param("profileId") String profileId, @Param("date") LocalDate date,
                       @Param("type") String type);
}
