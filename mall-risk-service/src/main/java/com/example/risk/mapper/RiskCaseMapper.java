package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RiskCase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface RiskCaseMapper extends BaseMapper<RiskCase> {
    @Update("UPDATE t_risk_case SET decision_no = #{decisionNo}, risk_level = #{riskLevel}, " +
            "risk_score = #{riskScore}, updated_time = #{now} WHERE id = #{id}")
    int updateLatest(@Param("id") Long id, @Param("decisionNo") String decisionNo,
                     @Param("riskLevel") String riskLevel, @Param("riskScore") Integer riskScore,
                     @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET status = 'OPEN', decision_no = #{decisionNo}, risk_level = #{riskLevel}, " +
            "risk_score = #{riskScore}, reopen_count = reopen_count + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND status IN ('RESOLVED', 'CLOSED')")
    int reopen(@Param("id") Long id, @Param("decisionNo") String decisionNo,
               @Param("riskLevel") String riskLevel, @Param("riskScore") Integer riskScore,
               @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET status = 'PROCESSING', assigned_to = #{operatorId}, " +
            "operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND status = #{expectedStatus} AND operation_version = #{expectedVersion}")
    int assignVersioned(@Param("id") Long id, @Param("operatorId") Long operatorId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("expectedVersion") Long expectedVersion,
                        @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET status = 'RESOLVED', assigned_to = #{operatorId}, " +
            "resolved_action = #{command}, resolve_reason = #{reason}, resolved_time = #{now}, " +
            "last_command_no = #{commandNo}, last_command_json = #{commandJson}, " +
            "command_status = 'PENDING_SEND', command_last_error = NULL, command_sent_time = NULL, " +
            "command_finished_time = NULL, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND status = #{expectedStatus} AND operation_version = #{expectedVersion}")
    int resolveVersioned(@Param("id") Long id, @Param("operatorId") Long operatorId,
                         @Param("command") String command, @Param("reason") String reason,
                         @Param("commandNo") String commandNo, @Param("commandJson") String commandJson,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET status = 'CLOSED', operation_version = operation_version + 1, " +
            "updated_time = #{now} WHERE id = #{id} AND status = #{expectedStatus} " +
            "AND command_status = #{expectedCommandStatus} AND operation_version = #{expectedVersion}")
    int closeVersioned(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                       @Param("expectedCommandStatus") String expectedCommandStatus,
                       @Param("expectedVersion") Long expectedVersion,
                       @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET status = 'OPEN', assigned_to = NULL, resolved_action = NULL, " +
            "resolve_reason = NULL, resolved_time = NULL, command_status = 'NONE', " +
            "command_last_error = NULL, command_sent_time = NULL, command_finished_time = NULL, " +
            "reopen_count = reopen_count + 1, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND status = #{expectedStatus} " +
            "AND command_status = #{expectedCommandStatus} AND operation_version = #{expectedVersion}")
    int reopenVersioned(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                        @Param("expectedCommandStatus") String expectedCommandStatus,
                        @Param("expectedVersion") Long expectedVersion,
                        @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_retry_count = command_retry_count + 1, " +
            "command_status = 'PENDING_SEND', command_last_error = NULL, command_sent_time = NULL, " +
            "command_finished_time = NULL, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} AND status = 'RESOLVED' " +
            "AND command_status = #{expectedCommandStatus} AND operation_version = #{expectedVersion} " +
            "AND command_status IN ('FAILED', 'COMMAND_FAILED')")
    int markCommandResentVersioned(@Param("id") Long id, @Param("commandNo") String commandNo,
                                   @Param("expectedCommandStatus") String expectedCommandStatus,
                                   @Param("expectedVersion") Long expectedVersion,
                                   @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_status = #{newStatus}, command_last_error = #{error}, " +
            "command_finished_time = #{now}, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} " +
            "AND command_status = #{expectedCommandStatus} AND status IN ('RESOLVED', 'CLOSED')")
    int manualComplete(@Param("id") Long id, @Param("commandNo") String commandNo,
                       @Param("expectedCommandStatus") String expectedCommandStatus,
                       @Param("newStatus") String newStatus, @Param("error") String error,
                       @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_status = 'SENT', command_sent_time = #{now}, " +
            "operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} AND status = 'RESOLVED' " +
            "AND command_status IN ('PENDING_SEND', 'FAILED')")
    int markCommandSent(@Param("id") Long id, @Param("commandNo") String commandNo,
                        @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_status = 'SUCCESS', command_last_error = NULL, " +
            "command_finished_time = #{now}, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} " +
            "AND status = 'RESOLVED' AND command_status IN ('PENDING_SEND', 'SENT', 'FAILED', 'COMMAND_FAILED')")
    int markCommandSuccess(@Param("id") Long id, @Param("commandNo") String commandNo,
                           @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_status = 'COMMAND_FAILED', command_last_error = #{error}, " +
            "command_finished_time = #{now}, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} " +
            "AND status = 'RESOLVED' AND command_status IN ('PENDING_SEND', 'SENT')")
    int markCommandExecutionFailed(@Param("id") Long id, @Param("commandNo") String commandNo,
                                   @Param("error") String error, @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_status = 'COMMAND_FAILED', command_last_error = #{error}, " +
            "command_finished_time = #{now}, operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} " +
            "AND status = 'RESOLVED' AND command_status IN ('PENDING_SEND', 'SENT', 'FAILED')")
    int markCommandDeadLetter(@Param("id") Long id, @Param("commandNo") String commandNo,
                              @Param("error") String error, @Param("now") LocalDateTime now);

    @Update("UPDATE t_risk_case SET command_status = 'FAILED', command_last_error = #{error}, " +
            "operation_version = operation_version + 1, updated_time = #{now} " +
            "WHERE id = #{id} AND last_command_no = #{commandNo} " +
            "AND status = 'RESOLVED' AND command_status IN ('PENDING_SEND', 'SENT')")
    int markCommandFailed(@Param("id") Long id, @Param("commandNo") String commandNo,
                          @Param("error") String error, @Param("now") LocalDateTime now);
}
