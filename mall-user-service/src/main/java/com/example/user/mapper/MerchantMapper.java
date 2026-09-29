package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {
    @Update("UPDATE t_merchant SET status = #{targetStatus}, reject_reason = #{rejectReason}, " +
            "updated_time = #{now} WHERE id = #{id} AND status = #{currentStatus}")
    int updateStatus(@Param("id") Long id, @Param("currentStatus") String currentStatus,
                     @Param("targetStatus") String targetStatus, @Param("rejectReason") String rejectReason,
                     @Param("now") LocalDateTime now);

    @Update("UPDATE t_merchant SET risk_status = #{riskStatus}, risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, updated_time = #{now} " +
            "WHERE id = #{merchantId} AND risk_status <> #{riskStatus}")
    int updateRiskStatus(@Param("merchantId") Long merchantId, @Param("riskStatus") String riskStatus,
                         @Param("riskLevel") String riskLevel, @Param("decisionNo") String decisionNo,
                         @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE t_merchant SET risk_status = #{riskStatus}, risk_level = #{riskLevel}, " +
            "risk_decision_no = #{decisionNo}, risk_reason = #{reason}, updated_time = #{now} " +
            "WHERE id = #{merchantId} AND status IN ('SUBMITTED', 'APPROVED', 'FROZEN') " +
            "AND risk_status <> #{riskStatus}")
    int updateRiskStatusIfAllowed(@Param("merchantId") Long merchantId, @Param("riskStatus") String riskStatus,
                                  @Param("riskLevel") String riskLevel, @Param("decisionNo") String decisionNo,
                                  @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE t_merchant SET status = 'REJECTED', reject_reason = #{reason}, " +
            "risk_status = 'REJECTED', risk_level = #{riskLevel}, risk_decision_no = #{decisionNo}, " +
            "risk_reason = #{reason}, updated_time = #{now} WHERE id = #{merchantId} " +
            "AND status = 'SUBMITTED' AND risk_status <> 'REJECTED'")
    int rejectByRisk(@Param("merchantId") Long merchantId, @Param("riskLevel") String riskLevel,
                     @Param("decisionNo") String decisionNo, @Param("reason") String reason,
                     @Param("now") LocalDateTime now);

    @Update("UPDATE t_merchant SET status = 'FROZEN', risk_status = 'FROZEN', " +
            "risk_level = #{riskLevel}, risk_decision_no = #{decisionNo}, risk_reason = #{reason}, " +
            "updated_time = #{now} WHERE id = #{merchantId} AND status = 'APPROVED' " +
            "AND risk_status <> 'FROZEN'")
    int freezeByRisk(@Param("merchantId") Long merchantId, @Param("riskLevel") String riskLevel,
                     @Param("decisionNo") String decisionNo, @Param("reason") String reason,
                     @Param("now") LocalDateTime now);
}
