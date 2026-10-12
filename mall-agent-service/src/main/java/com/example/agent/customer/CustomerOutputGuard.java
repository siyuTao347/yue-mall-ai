package com.example.agent.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import static com.example.agent.customer.CustomerJson.*;

/** Validate evidence and the entire exposed payload, including citations and tool summaries. */
public final class CustomerOutputGuard {
    private CustomerOutputGuard() {}
    private static final Pattern UNSAFE = Pattern.compile(
        "(?i)(https?://|www\\.|加.{0,3}(微信|QQ)|站外交易|私下交易|忽略.{0,8}(指令|规则)|"
        + "(保证|承诺|一定|立即|已经|已为你).{0,8}(退款|赔偿|处罚)|"
        + "(密码|卡密|支付凭证|password|token)\\s*[:：=]\\s*(?!\\[已隐藏\\])[^\\s，。]+|"
        + "bearer\\s+(?!\\[已隐藏\\])[a-z0-9._-]+)");

    static boolean safeText(String text) { return !UNSAFE.matcher(text).find(); }

    static void validate(ObjectNode task, ObjectNode result) {
        if (!task.path("intentInstanceId").equals(result.path("intentInstanceId"))
            || !result.path("answer").isTextual() || result.path("answer").asText().isBlank()
            || !result.path("citations").isArray() || !result.path("evidence").isArray())
            throw error(422, "AGENT_OUTPUT_INVALID", "响应格式不正确");
        if (!safePayload(result)) throw error(422, "AGENT_OUTPUT_UNSAFE", "回答未通过安全校验");
        if (!"COMPLETED".equals(result.path("resolutionState").asText())) return;
        if (result.hasNonNull("bizNo")) {
            String key = "DISPUTE".equals(result.path("bizType").asText()) ? "disputeNo" : "orderNo";
            String no = result.path("bizNo").asText();
            boolean supported = false;
            for (var evidence : result.path("evidence"))
                supported |= no.equals(evidence.path(key).asText()) && no.equals(task.path("slots").path(key).asText());
            if (!supported) throw error(422, "AGENT_EVIDENCE_MISSING", "业务事实缺少工具依据");
        }
        Set<String> sources = new HashSet<>();
        for (var source : result.path("citations")) {
            if (!source.path("intentInstanceId").equals(task.path("intentInstanceId"))
                || source.path("docNo").asText().isBlank() || source.path("docVersion").asInt() < 1
                || source.path("content").asText().isBlank() || !sources.add(source.path("sourceNo").asText()))
                throw error(422, "AGENT_CITATION_INVALID", "引用格式不正确");
        }
        if (result.path("policyAnswer").asBoolean() && sources.isEmpty())
            throw error(422, "AGENT_CITATION_MISSING", "规则回答缺少知识引用");
    }

    static boolean safePayload(JsonNode value) {
        if (value.isTextual()) return safeText(value.asText()) && redact(value.asText()).equals(value.asText());
        for (var child : value) if (!safePayload(child)) return false;
        return true;
    }

    static void redactPayload(JsonNode value) {
        if (value instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                if (entry.getValue().isTextual()) object.put(entry.getKey(), redact(entry.getValue().asText()));
                else redactPayload(entry.getValue());
            });
        } else if (value instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isTextual()) array.set(i, node(redact(array.get(i).asText())));
                else redactPayload(array.get(i));
            }
        }
    }
}
