package com.example.risk.service;

import api.common.PageResult;
import api.risk.RelationGraphDTO;
import api.risk.RiskRuleHitDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.risk.dto.RiskCaseDetailDTO;
import com.example.risk.dto.RiskCaseListQuery;
import com.example.risk.dto.RiskCaseNoteDTO;
import com.example.risk.dto.RiskCaseSummaryDTO;
import com.example.risk.dto.RiskCommandSnapshotDTO;
import com.example.risk.dto.RiskDecisionEvidenceDTO;
import com.example.risk.dto.RiskEventEvidenceDTO;
import com.example.risk.dto.RiskRelationNodeDTO;
import com.example.risk.entity.RiskCase;
import com.example.risk.entity.RiskCaseNote;
import com.example.risk.entity.RiskDecision;
import com.example.risk.entity.RiskEvent;
import com.example.risk.exception.RiskApiException;
import com.example.risk.mapper.RiskCaseMapper;
import com.example.risk.mapper.RiskCaseNoteMapper;
import com.example.risk.mapper.RiskDecisionMapper;
import com.example.risk.mapper.RiskEventMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 案件列表、详情与证据聚合。只读服务，不修改案件状态。
 */
@Service
public class RiskCaseQueryService {
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_EVIDENCE_TEXT_LENGTH = 200;
    private static final Set<String> PAYLOAD_WHITELIST = Set.of(
            "orderNo", "itemId", "merchantId", "userId", "amount", "currency", "payMethod", "channel",
            "deliveryMode", "assetType", "category", "productName", "reason", "source", "clientType",
            "payToDeliverSeconds", "viewToConfirmSeconds", "settleToWithdrawMinutes",
            "categoryMedianPrice", "orderRiskStatus");
    private static final String DEAD_LETTER_STATUS = "DEAD_LETTER";
    private static final String RAW_DEAD_LETTER_STATUS = "COMMAND_FAILED";

    private final RiskCaseMapper caseMapper;
    private final RiskCaseNoteMapper noteMapper;
    private final RiskDecisionMapper decisionMapper;
    private final RiskEventMapper eventMapper;
    private final RiskIdentityService identityService;
    private final RiskDictionaryService dictionary;
    private final ObjectMapper objectMapper;

    public RiskCaseQueryService(RiskCaseMapper caseMapper, RiskCaseNoteMapper noteMapper,
                                RiskDecisionMapper decisionMapper, RiskEventMapper eventMapper,
                                RiskIdentityService identityService, RiskDictionaryService dictionary,
                                ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.noteMapper = noteMapper;
        this.decisionMapper = decisionMapper;
        this.eventMapper = eventMapper;
        this.identityService = identityService;
        this.dictionary = dictionary;
        this.objectMapper = objectMapper;
    }

    public PageResult<RiskCaseSummaryDTO> listCases(RiskCaseListQuery query, int page, int pageSize) {
        String commandStatus = normalizeCommandStatus(query == null ? null : query.commandStatus());
        String keyword = query == null || query.keyword() == null ? null : query.keyword().trim();
        LambdaQueryWrapper<RiskCase> wrapper = new LambdaQueryWrapper<RiskCase>()
                .eq(query != null && query.status() != null, RiskCase::getStatus,
                        query == null ? null : query.status())
                .eq(query != null && query.scene() != null, RiskCase::getScene,
                        query == null ? null : query.scene())
                .eq(query != null && query.riskLevel() != null, RiskCase::getRiskLevel,
                        query == null ? null : query.riskLevel())
                .eq(commandStatus != null, RiskCase::getCommandStatus, commandStatus)
                .eq(query != null && query.subjectType() != null, RiskCase::getSubjectType,
                        query == null ? null : query.subjectType())
                .eq(query != null && query.subjectId() != null, RiskCase::getSubjectId,
                        query == null ? null : query.subjectId())
                .eq(query != null && query.bizNo() != null, RiskCase::getBizNo,
                        query == null ? null : query.bizNo())
                .eq(query != null && query.assignedTo() != null, RiskCase::getAssignedTo,
                        query == null ? null : query.assignedTo())
                .ge(query != null && query.timeRange() != null && query.timeRange().fromTime() != null,
                        RiskCase::getCreatedTime, query == null || query.timeRange() == null
                                ? null : query.timeRange().fromTime())
                .le(query != null && query.timeRange() != null && query.timeRange().toTime() != null,
                        RiskCase::getCreatedTime, query == null || query.timeRange() == null
                                ? null : query.timeRange().toTime());
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(nested -> nested.likeRight(RiskCase::getCaseNo, keyword)
                    .or().likeRight(RiskCase::getDecisionNo, keyword)
                    .or().likeRight(RiskCase::getBizNo, keyword));
        }
        wrapper.orderByDesc(RiskCase::getUpdatedTime).orderByDesc(RiskCase::getId);
        Page<RiskCase> result = caseMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<RiskCaseSummaryDTO> records = result.getRecords().stream().map(this::caseSummary).toList();
        return PageResult.of(records, result.getTotal(), page, pageSize);
    }

    public RiskCaseSummaryDTO caseSummary(RiskCase riskCase) {
        String commandStatus = riskCase.getCommandStatus();
        return new RiskCaseSummaryDTO(
                riskCase.getId(),
                riskCase.getCaseNo(),
                riskCase.getDecisionNo(),
                riskCase.getScene(),
                dictionary.sceneText(riskCase.getScene()),
                riskCase.getBizType(),
                riskCase.getBizNo(),
                riskCase.getSubjectType(),
                riskCase.getSubjectId(),
                riskCase.getRiskLevel(),
                dictionary.riskLevelText(riskCase.getRiskLevel()),
                riskCase.getRiskScore(),
                riskCase.getStatus(),
                dictionary.caseStatusText(riskCase.getStatus()),
                riskCase.getAssignedTo(),
                riskCase.getResolvedAction(),
                riskCase.getResolvedAction() == null ? null : dictionary.commandText(riskCase.getResolvedAction()),
                riskCase.getResolveReason(),
                riskCase.getResolvedTime(),
                riskCase.getLastCommandNo(),
                commandStatus,
                dictionary.commandStatusText(commandStatus),
                dictionary.commandTone(commandStatus),
                riskCase.getCommandRetryCount(),
                riskCase.getCommandLastError(),
                riskCase.getCommandSentTime(),
                riskCase.getCommandFinishedTime(),
                riskCase.getOperationVersion(),
                riskCase.getReopenCount(),
                riskCase.getCreatedTime(),
                riskCase.getUpdatedTime());
    }

    public RiskCaseDetailDTO detail(String caseNo) {
        RiskCase riskCase = requireCase(caseNo);
        List<RiskCaseNote> notes = noteMapper.selectList(new LambdaQueryWrapper<RiskCaseNote>()
                .eq(RiskCaseNote::getCaseNo, caseNo)
                .orderByAsc(RiskCaseNote::getId));

        RiskDecision decision = null;
        if (riskCase.getDecisionNo() != null) {
            decision = decisionMapper.selectOne(new LambdaQueryWrapper<RiskDecision>()
                    .eq(RiskDecision::getDecisionNo, riskCase.getDecisionNo()));
        }
        RiskEvent event = loadEvent(riskCase, decision);

        List<RiskCaseNoteDTO> noteDTOs = notes.stream().map(this::noteDTO).toList();
        RiskCommandSnapshotDTO command = commandSnapshot(riskCase, notes);
        List<RiskRelationNodeDTO> relations = relations(riskCase);

        Map<String, Boolean> completeness = new LinkedHashMap<>();
        completeness.put("decision", decision != null);
        completeness.put("event", event != null);
        completeness.put("notes", !noteDTOs.isEmpty());
        completeness.put("command", command != null);
        completeness.put("relations", !relations.isEmpty());

        return new RiskCaseDetailDTO(
                caseSummary(riskCase),
                decisionDTO(decision, riskCase),
                eventDTO(event),
                noteDTOs,
                command,
                relations,
                completeness);
    }

    public RiskCase requireCase(String caseNo) {
        RiskCase riskCase = caseMapper.selectOne(new LambdaQueryWrapper<RiskCase>()
                .eq(RiskCase::getCaseNo, caseNo));
        if (riskCase == null) {
            throw RiskApiException.notFound(RiskApiException.CASE_NOT_FOUND, "风控案件不存在");
        }
        return riskCase;
    }

    private RiskEvent loadEvent(RiskCase riskCase, RiskDecision decision) {
        if (decision != null && decision.getEventNo() != null) {
            RiskEvent event = eventMapper.selectOne(new LambdaQueryWrapper<RiskEvent>()
                    .eq(RiskEvent::getEventNo, decision.getEventNo()));
            if (event != null) {
                return event;
            }
        }
        LambdaQueryWrapper<RiskEvent> wrapper = new LambdaQueryWrapper<RiskEvent>()
                .eq(riskCase.getBizType() != null, RiskEvent::getBizType, riskCase.getBizType())
                .eq(riskCase.getBizNo() != null, RiskEvent::getBizNo, riskCase.getBizNo())
                .orderByDesc(RiskEvent::getId)
                .last("LIMIT 1");
        if (riskCase.getBizType() == null && riskCase.getBizNo() == null) {
            return null;
        }
        return eventMapper.selectOne(wrapper);
    }

    private RiskDecisionEvidenceDTO decisionDTO(RiskDecision decision, RiskCase riskCase) {
        if (decision == null) {
            return null;
        }
        return new RiskDecisionEvidenceDTO(
                decision.getDecisionNo(),
                decision.getAction(),
                decision.getAction() == null ? null : dictionary.commandText(decision.getAction()),
                decision.getRiskLevel(),
                dictionary.riskLevelText(decision.getRiskLevel()),
                decision.getRiskScore(),
                firstNonBlank(readText(decision.getEvidenceJson(), "reason"), riskCase.getResolveReason()),
                readHitRules(decision.getHitRulesJson()),
                readStringList(decision.getMissingMetricsJson()),
                decision.getDegraded(),
                decision.getCreatedTime());
    }

    private RiskEventEvidenceDTO eventDTO(RiskEvent event) {
        if (event == null) {
            return null;
        }
        JsonNode payload = readTree(event.getPayloadJson());
        Map<String, Object> context = new LinkedHashMap<>();
        if (payload != null && payload.isObject()) {
            payload.fields().forEachRemaining(entry -> {
                if (PAYLOAD_WHITELIST.contains(entry.getKey())) {
                    context.put(entry.getKey(), sanitizeValue(entry.getValue()));
                }
            });
        }
        return new RiskEventEvidenceDTO(
                event.getEventNo(),
                event.getScene(),
                dictionary.sceneText(event.getScene()),
                event.getEventType(),
                event.getEventPhase(),
                event.getBizType(),
                event.getBizNo(),
                subjectOf(event, context),
                event.getAmount(),
                maskHash(event.getIpHash()),
                maskHash(event.getDeviceHash()),
                context,
                event.getOccurredTime(),
                event.getConfirmedTime());
    }

    private RiskCaseNoteDTO noteDTO(RiskCaseNote note) {
        return new RiskCaseNoteDTO(
                note.getId(),
                note.getNoteType(),
                dictionary.noteTypeText(note.getNoteType()),
                dictionary.noteTone(note.getNoteType()),
                note.getOperatorId(),
                note.getOperatorName(),
                truncate(note.getContent(), MAX_TEXT_LENGTH),
                note.getBeforeStatus(),
                note.getAfterStatus(),
                sanitizeEvidence(readTree(note.getEvidenceJson())),
                note.getCreatedTime());
    }

    private RiskCommandSnapshotDTO commandSnapshot(RiskCase riskCase, List<RiskCaseNote> notes) {
        String commandStatus = riskCase.getCommandStatus();
        if (commandStatus == null || "NONE".equals(commandStatus)) {
            return null;
        }
        String command = riskCase.getResolvedAction();
        String displayStatus = commandStatus;
        if ("COMMAND_FAILED".equals(commandStatus) && isDeadLetter(riskCase.getLastCommandNo(), notes)) {
            displayStatus = DEAD_LETTER_STATUS;
        }
        return new RiskCommandSnapshotDTO(
                riskCase.getLastCommandNo(),
                command,
                command == null ? null : dictionary.commandText(command),
                displayStatus,
                dictionary.commandStatusText(displayStatus),
                commandStatus,
                dictionary.commandTone(displayStatus),
                riskCase.getCommandRetryCount(),
                riskCase.getCommandLastError(),
                riskCase.getCommandSentTime(),
                riskCase.getCommandFinishedTime());
    }

    private boolean isDeadLetter(String commandNo, List<RiskCaseNote> notes) {
        if (commandNo == null) {
            return false;
        }
        for (int i = notes.size() - 1; i >= 0; i--) {
            RiskCaseNote note = notes.get(i);
            if (!"COMMAND_DEAD_LETTER".equals(note.getNoteType())) {
                continue;
            }
            JsonNode evidence = readTree(note.getEvidenceJson());
            if (evidence == null || evidence.path("commandNo").asText("").equals(commandNo)) {
                return true;
            }
        }
        return false;
    }

    private List<RiskRelationNodeDTO> relations(RiskCase riskCase) {
        if (!"USER".equals(riskCase.getSubjectType()) || riskCase.getSubjectId() == null
                || riskCase.getSubjectId() <= 0) {
            return List.of();
        }
        int depth = Math.min(Math.max(1, dictionary.relationMaxDepth()), 2);
        RelationGraphDTO graph = identityService.getRelations(riskCase.getSubjectId(), depth);
        if (graph == null || graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, dictionary.relationMaxNodes());
        List<RiskRelationNodeDTO> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        result.add(new RiskRelationNodeDTO("SUBJECT", riskCase.getSubjectId(), null, "SELF",
                100, graph.getEdges().size()));
        for (Map<String, Object> edge : graph.getEdges()) {
            if (result.size() >= limit) {
                break;
            }
            Long source = asLong(edge.get("sourceUserId"));
            Long target = asLong(edge.get("targetUserId"));
            Long nodeId = riskCase.getSubjectId().equals(source) ? target
                    : riskCase.getSubjectId().equals(target) ? source : target;
            if (nodeId == null || !seen.add(nodeId)) {
                continue;
            }
            result.add(new RiskRelationNodeDTO("USER", nodeId, null,
                    asString(edge.get("relationType")), asInt(edge.get("weight")),
                    asInt(edge.get("hitCount"))));
        }
        return result;
    }

    private String normalizeCommandStatus(String commandStatus) {
        if (DEAD_LETTER_STATUS.equals(commandStatus)) {
            return RAW_DEAD_LETTER_STATUS;
        }
        return commandStatus;
    }

    private String subjectOf(RiskEvent event, Map<String, Object> context) {
        if (event.getOrderNo() != null) {
            return "订单 " + event.getOrderNo();
        }
        if (event.getWithdrawNo() != null) {
            return "提现 " + event.getWithdrawNo();
        }
        Object itemId = context.get("itemId");
        if (itemId != null) {
            return "商品 " + itemId;
        }
        if (event.getUserId() != null) {
            return "用户 " + event.getUserId();
        }
        if (event.getMerchantId() != null) {
            return "商家 " + event.getMerchantId();
        }
        return null;
    }

    private Object sanitizeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return truncate(node.asText(), MAX_EVIDENCE_TEXT_LENGTH);
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.isBoolean() ? node.asBoolean() : node.numberValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> {
                if (list.size() < 10) {
                    list.add(sanitizeValue(item));
                }
            });
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (map.size() < 10) {
                    map.put(entry.getKey(), sanitizeValue(entry.getValue()));
                }
            });
            return map;
        }
        return truncate(node.asText(), MAX_EVIDENCE_TEXT_LENGTH);
    }

    private Map<String, Object> sanitizeEvidence(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) sanitizeValue(node);
        return value == null ? Map.of() : value;
    }

    private List<RiskRuleHitDTO> readHitRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RiskRuleHitDTO>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String readText(String json, String field) {
        JsonNode node = readTree(json);
        if (node == null || !node.isObject()) {
            return null;
        }
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String maskHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        return hash.length() <= 8 ? hash.charAt(0) + "***" : hash.substring(0, 8) + "***";
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
