package com.example.risk.provider;

import api.risk.RiskDecisionResult;
import api.risk.RiskCommandResultDTO;
import api.risk.RiskDubboService;
import api.risk.RiskEvaluateRequest;
import api.risk.RelationGraphDTO;
import api.risk.SensitiveWordDTO;
import api.risk.SubjectRiskDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.RiskSubject;
import com.example.risk.entity.SensitiveWord;
import com.example.risk.mapper.RiskSubjectMapper;
import com.example.risk.mapper.SensitiveWordMapper;
import com.example.risk.service.RiskCommandService;
import com.example.risk.service.RiskEvaluateService;
import com.example.risk.service.RiskIdentityService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class RiskDubboServiceImpl implements RiskDubboService {
    private final RiskEvaluateService evaluateService;
    private final RiskIdentityService identityService;
    private final RiskSubjectMapper subjectMapper;
    private final SensitiveWordMapper sensitiveWordMapper;
    private final RiskCommandService commandService;

    public RiskDubboServiceImpl(RiskEvaluateService evaluateService, RiskIdentityService identityService,
                                RiskSubjectMapper subjectMapper, SensitiveWordMapper sensitiveWordMapper,
                                RiskCommandService commandService) {
        this.evaluateService = evaluateService;
        this.identityService = identityService;
        this.subjectMapper = subjectMapper;
        this.sensitiveWordMapper = sensitiveWordMapper;
        this.commandService = commandService;
    }

    @Override
    public RiskDecisionResult evaluate(RiskEvaluateRequest request) {
        return evaluateService.evaluate(request);
    }

    @Override
    public RiskDecisionResult recordEvent(RiskEvaluateRequest request) {
        return evaluateService.recordEvent(request);
    }

    @Override
    public void confirmEvent(String eventNo) {
        evaluateService.confirmEvent(eventNo);
    }

    @Override
    public SubjectRiskDTO getSubjectRisk(String subjectType, Long subjectId) {
        if (subjectType == null || subjectType.isBlank() || subjectId == null) {
            return null;
        }
        RiskSubject subject = subjectMapper.selectOne(new LambdaQueryWrapper<RiskSubject>()
                .eq(RiskSubject::getSubjectType, subjectType)
                .eq(RiskSubject::getSubjectId, subjectId));
        return subject == null ? null : SubjectRiskDTO.builder()
                .subjectType(subject.getSubjectType())
                .subjectId(subject.getSubjectId())
                .riskStatus(subject.getRiskStatus())
                .riskLevel(subject.getRiskLevel())
                .riskScore(subject.getRiskScore())
                .lastDecisionNo(subject.getLastDecisionNo())
                .riskReason(subject.getRiskReason())
                .build();
    }

    @Override
    public RelationGraphDTO getRelations(Long userId, int depth) {
        if (userId == null) {
            return RelationGraphDTO.builder().depth(1).truncated(false).nodes(List.of()).edges(List.of()).build();
        }
        return identityService.getRelations(userId, depth);
    }

    @Override
    public List<SensitiveWordDTO> getSensitiveWords() {
        return sensitiveWordMapper.selectList(new LambdaQueryWrapper<SensitiveWord>()
                        .eq(SensitiveWord::getEnabled, true))
                .stream()
                .map(word -> SensitiveWordDTO.builder()
                        .wordCode(word.getWordCode())
                        .category(word.getCategory())
                        .word(word.getWord())
                        .build())
                .toList();
    }

    @Override
    public void confirmCommand(RiskCommandResultDTO result) {
        commandService.confirmCommand(result);
    }
}
