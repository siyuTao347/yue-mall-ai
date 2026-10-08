package com.example.risk.service;

import com.example.risk.dto.RiskCommandPolicyDTO;
import com.example.risk.dto.RiskDictionaryDTO;
import com.example.risk.dto.RiskDictionaryItemDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控工作台状态/场景/命令字典，供前端统一渲染中文文案与颜色语义。
 */
@Service
public class RiskDictionaryService {
    public static final List<String> FUND_RELATED_COMMANDS =
            List.of("DELAY_SETTLE", "FREEZE", "LIMIT", "REJECT", "APPROVE");

    private static final Map<String, List<String>> SCENE_COMMANDS = sceneCommands();
    private static final Map<String, String> COMMAND_TEXTS = Map.of(
            "APPROVE", "通过",
            "REJECT", "驳回",
            "FREEZE", "冻结",
            "LIMIT", "限制",
            "DELAY_SETTLE", "延迟结算");

    private static final Map<String, RiskDictionaryItemDTO> CASE_STATUS = index(List.of(
            new RiskDictionaryItemDTO("OPEN", "待处理", "warning", 1, null),
            new RiskDictionaryItemDTO("PROCESSING", "处理中", "info", 2, null),
            new RiskDictionaryItemDTO("RESOLVED", "已处理", "success", 3, null),
            new RiskDictionaryItemDTO("CLOSED", "已关闭", "default", 4, null)));

    private static final Map<String, RiskDictionaryItemDTO> COMMAND_STATUS = index(List.of(
            new RiskDictionaryItemDTO("NONE", "无命令", "default", 1, null),
            new RiskDictionaryItemDTO("PENDING_SEND", "待发送", "info", 2, null),
            new RiskDictionaryItemDTO("SENT", "已发送", "info", 3, null),
            new RiskDictionaryItemDTO("SUCCESS", "执行成功", "success", 4, null),
            new RiskDictionaryItemDTO("FAILED", "发送失败", "danger", 5, null),
            new RiskDictionaryItemDTO("COMMAND_FAILED", "执行失败", "danger", 6, null),
            new RiskDictionaryItemDTO("DEAD_LETTER", "死信待人工处理", "danger", 7, null)));

    private static final Map<String, RiskDictionaryItemDTO> RISK_LEVEL = index(List.of(
            new RiskDictionaryItemDTO("LOW", "低", "success", 1, null),
            new RiskDictionaryItemDTO("MEDIUM", "中", "warning", 2, null),
            new RiskDictionaryItemDTO("HIGH", "高", "danger", 3, null),
            new RiskDictionaryItemDTO("CRITICAL", "严重", "danger", 4, null)));

    private static final Map<String, RiskDictionaryItemDTO> NOTE_TYPE = index(List.of(
            new RiskDictionaryItemDTO("CREATE", "自动创建案件", "info", 1, null),
            new RiskDictionaryItemDTO("ASSIGN", "认领案件", "info", 2, null),
            new RiskDictionaryItemDTO("PROCESS", "更新最新决策", "info", 3, null),
            new RiskDictionaryItemDTO("RESOLVE", "提交处理命令", "warning", 4, null),
            new RiskDictionaryItemDTO("CLOSE", "关闭案件", "success", 5, null),
            new RiskDictionaryItemDTO("REOPEN", "重开案件", "warning", 6, null),
            new RiskDictionaryItemDTO("COMMAND_SUCCESS", "命令执行成功", "success", 7, null),
            new RiskDictionaryItemDTO("COMMAND_FAILED", "命令执行失败", "danger", 8, null),
            new RiskDictionaryItemDTO("COMMAND_DEAD_LETTER", "命令进入死信", "danger", 9, null),
            new RiskDictionaryItemDTO("COMMAND_RESEND", "人工重发命令", "warning", 10, null),
            new RiskDictionaryItemDTO("COMMAND_MANUAL_COMPLETE", "人工确认处理结果", "warning", 11, null)));

    private static final Map<String, String> SCENE_TEXTS = Map.of(
            "ORDER", "订单",
            "PAYMENT", "支付",
            "DELIVERY", "交付",
            "CONFIRM", "确认",
            "DISPUTE", "售后",
            "LISTING", "商品",
            "REGISTER", "注册",
            "LOGIN", "登录",
            "WITHDRAW", "提现",
            "MERCHANT", "商家");

    @Value("${risk.workbench.command-poll-interval-seconds:5}")
    private int pollIntervalSeconds;
    @Value("${risk.workbench.command-poll-max-duration-seconds:300}")
    private int pollMaxDurationSeconds;
    @Value("${risk.workbench.manual-complete-enabled:true}")
    private boolean manualCompleteEnabled;
    @Value("${risk.workbench.relation-max-nodes:20}")
    private int relationMaxNodes;
    @Value("${risk.workbench.relation-max-depth:1}")
    private int relationMaxDepth;

    public RiskDictionaryDTO dictionary() {
        return new RiskDictionaryDTO(
                List.copyOf(CASE_STATUS.values()),
                List.copyOf(COMMAND_STATUS.values()),
                List.copyOf(RISK_LEVEL.values()),
                SCENE_COMMANDS.entrySet().stream()
                        .map(entry -> new RiskDictionaryItemDTO(entry.getKey(),
                                sceneText(entry.getKey()), "info", sceneSort(entry.getKey()), entry.getValue()))
                        .toList(),
                List.copyOf(NOTE_TYPE.values()),
                new RiskCommandPolicyDTO(pollIntervalSeconds, pollMaxDurationSeconds,
                        manualCompleteEnabled, relationMaxNodes, FUND_RELATED_COMMANDS));
    }

    public List<String> commandsForScene(String scene) {
        return SCENE_COMMANDS.getOrDefault(scene, List.of("APPROVE", "FREEZE"));
    }

    public String commandText(String command) {
        return COMMAND_TEXTS.getOrDefault(command, command);
    }

    public String caseStatusText(String code) {
        return text(CASE_STATUS, code);
    }

    public String commandStatusText(String code) {
        return text(COMMAND_STATUS, code);
    }

    public String riskLevelText(String code) {
        return text(RISK_LEVEL, code);
    }

    public String sceneText(String code) {
        return SCENE_TEXTS.getOrDefault(code, code);
    }

    public String noteTypeText(String code) {
        return text(NOTE_TYPE, code);
    }

    public String commandTone(String code) {
        RiskDictionaryItemDTO item = COMMAND_STATUS.get(code);
        return item == null ? "default" : item.tone();
    }

    public String noteTone(String code) {
        RiskDictionaryItemDTO item = NOTE_TYPE.get(code);
        return item == null ? "default" : item.tone();
    }

    public boolean manualCompleteEnabled() {
        return manualCompleteEnabled;
    }

    public int relationMaxNodes() {
        return relationMaxNodes;
    }

    public int relationMaxDepth() {
        return relationMaxDepth;
    }

    private static String text(Map<String, RiskDictionaryItemDTO> dictionary, String code) {
        RiskDictionaryItemDTO item = dictionary.get(code);
        return item == null ? code : item.text();
    }

    private static int sceneSort(String scene) {
        int index = 0;
        for (String key : SCENE_COMMANDS.keySet()) {
            if (key.equals(scene)) {
                return index + 1;
            }
            index++;
        }
        return index + 1;
    }

    private static Map<String, RiskDictionaryItemDTO> index(List<RiskDictionaryItemDTO> items) {
        Map<String, RiskDictionaryItemDTO> result = new LinkedHashMap<>();
        for (RiskDictionaryItemDTO item : items) {
            result.put(item.code(), item);
        }
        return result;
    }

    private static Map<String, List<String>> sceneCommands() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("ORDER", List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"));
        result.put("PAYMENT", List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"));
        result.put("DELIVERY", List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"));
        result.put("CONFIRM", List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"));
        result.put("DISPUTE", List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"));
        result.put("WITHDRAW", List.of("APPROVE", "FREEZE", "LIMIT", "DELAY_SETTLE"));
        result.put("LISTING", List.of("APPROVE", "REJECT", "FREEZE"));
        result.put("MERCHANT", List.of("APPROVE", "REJECT", "FREEZE", "LIMIT"));
        result.put("REGISTER", List.of("APPROVE", "FREEZE"));
        result.put("LOGIN", List.of("APPROVE", "FREEZE"));
        return result;
    }
}
