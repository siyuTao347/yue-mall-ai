package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.WithdrawRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface WithdrawRequestMapper extends BaseMapper<WithdrawRequest> {
    @Update("UPDATE t_withdraw_request SET risk_status = #{riskStatus}, risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, updated_time = #{now} " +
            "WHERE withdraw_no = #{withdrawNo} AND risk_status <> #{riskStatus}")
    int updateRiskStatus(@Param("withdrawNo") String withdrawNo, @Param("riskStatus") String riskStatus,
                         @Param("riskLevel") String riskLevel, @Param("decisionNo") String decisionNo,
                         @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE t_withdraw_request SET risk_status = #{riskStatus}, risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, updated_time = #{now} " +
            "WHERE withdraw_no = #{withdrawNo} AND status = 'SUBMITTED' AND risk_status <> #{riskStatus}")
    int updateRiskStatusIfAllowed(@Param("withdrawNo") String withdrawNo, @Param("riskStatus") String riskStatus,
                                  @Param("riskLevel") String riskLevel, @Param("decisionNo") String decisionNo,
                                  @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE t_withdraw_request SET status = 'REJECTED', audit_reason = #{reason}, " +
            "audit_admin_id = #{adminId}, risk_status = 'REJECTED', risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, updated_time = #{now} " +
            "WHERE withdraw_no = #{withdrawNo} " +
            "AND status = 'SUBMITTED' AND risk_status <> 'REJECTED'")
    int rejectByRisk(@Param("withdrawNo") String withdrawNo, @Param("riskLevel") String riskLevel,
                     @Param("decisionNo") String decisionNo, @Param("reason") String reason,
                     @Param("adminId") Long adminId, @Param("now") LocalDateTime now);

    @Update("UPDATE t_withdraw_request SET payout_delay_until = GREATEST(payout_delay_until, #{until}), " +
            "updated_time = #{now} WHERE withdraw_no = #{withdrawNo} " +
            "AND status = 'SUBMITTED' AND (payout_delay_until IS NULL OR payout_delay_until < #{until})")
    int delayPayout(@Param("withdrawNo") String withdrawNo, @Param("until") LocalDateTime until,
                    @Param("now") LocalDateTime now);
}
