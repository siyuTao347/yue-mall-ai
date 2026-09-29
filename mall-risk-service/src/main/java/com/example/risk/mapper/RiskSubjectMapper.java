package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RiskSubject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RiskSubjectMapper extends BaseMapper<RiskSubject> {
    @Insert("INSERT INTO t_risk_subject(subject_type, subject_id, risk_status, risk_level, risk_score, " +
            "last_decision_no, risk_reason) VALUES(#{subjectType}, #{subjectId}, #{riskStatus}, #{riskLevel}, " +
            "#{riskScore}, #{decisionNo}, #{reason}) ON DUPLICATE KEY UPDATE risk_status = VALUES(risk_status), " +
            "risk_level = VALUES(risk_level), risk_score = VALUES(risk_score), " +
            "last_decision_no = VALUES(last_decision_no), risk_reason = VALUES(risk_reason)")
    int upsert(@Param("subjectType") String subjectType, @Param("subjectId") Long subjectId,
               @Param("riskStatus") String riskStatus, @Param("riskLevel") String riskLevel,
               @Param("riskScore") Integer riskScore, @Param("decisionNo") String decisionNo,
               @Param("reason") String reason);
}
