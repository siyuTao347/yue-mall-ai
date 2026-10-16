package com.example.agent.customer.memory;

import com.example.agent.customer.memory.MemoryModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.example.agent.customer.CustomerJson.*;

@Component
public class PreferenceExtractor {
    private static final Logger log = LoggerFactory.getLogger(PreferenceExtractor.class);

    private static final String SYSTEM_PROMPT = """
        你是一个电商客服对话长期购买偏好提取器。
        目标：仅提取用户本人明确表达、适合未来购买咨询复用的常态偏好。
        规则约束：
        1. 必须严格区分常态表达（平时/通常/以后/习惯）与单次任务条件（这次/今天/帮朋友买）。
        2. 必须区分用户本人偏好（beneficiary=SELF）与第三方（替朋友/给别人买）。第三方偏好不得提取。
        3. 严禁从助手建议、商品详情、工具回包或单次咨询推断偏好。
        4. 严禁提取密码、凭证、手机号、联系方式、具体订单号或敏感个人属性。
        5. 每项候选必须给出用户当前发言中的原句作为 quote；证据不足返回空 candidates。
        6. 输出格式必须严格为 JSON：
        {
          "schemaVersion": "preference-extraction-v1",
          "candidates": [
            {
              "operation": "UPSERT",
              "type": "DELIVERY_PREFERENCE",
              "scope": {"beneficiary": "SELF", "categoryCode": "GAME_TOPUP"},
              "value": {"deliveryMode": "AUTO"},
              "polarity": "PREFER",
              "durability": "HABITUAL",
              "evidence": {"quote": "用户原句片段"},
              "confidence": 0.95
            }
          ]
        }
        支持的 type 仅限：CATEGORY_PREFERENCE, DELIVERY_PREFERENCE, PLATFORM_PREFERENCE, SPEC_PREFERENCE, PRICE_RANGE, EXCLUSION
        """;

    private final MemoryExtractionClient extractionClient;

    public PreferenceExtractor(MemoryExtractionClient extractionClient) {
        this.extractionClient = extractionClient;
    }

    public List<ValidatedCandidate> extract(String userMessage, MemoryDeadline deadline) {
        if (userMessage == null || userMessage.isBlank()) {
            return Collections.emptyList();
        }

        // Fast rule pre-filter: Skip LLM call if message contains no habitual preference signals
        if (!FastRuleFilter.mayContainPreference(userMessage)) {
            log.debug("FastRuleFilter bypassed extraction for message: {}", userMessage);
            return Collections.emptyList();
        }

        String rawJson;
        try {
            rawJson = extractionClient.callExtraction(SYSTEM_PROMPT, "用户发言: " + userMessage, deadline);
        } catch (Exception e) {
            log.warn("Memory extraction LLM call failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        return parseAndValidate(rawJson, userMessage);
    }

    public List<ValidatedCandidate> parseAndValidate(String rawJson, String userMessage) {
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            // Strip markdown code fences if model output contains them
            String cleaned = rawJson.strip();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();

            JsonNode root = read(cleaned);
            JsonNode candidatesNode = root.path("candidates");
            if (!candidatesNode.isArray() || candidatesNode.isEmpty()) {
                return Collections.emptyList();
            }

            List<ValidatedCandidate> validated = new ArrayList<>();
            for (JsonNode c : candidatesNode) {
                if (validated.size() >= 5) break;

                String op = c.path("operation").asText("UPSERT");
                if (!"UPSERT".equalsIgnoreCase(op)) continue;

                String typeStr = c.path("type").asText();
                MemoryType type;
                try {
                    type = MemoryType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                // Check durability: must be HABITUAL
                String durability = c.path("durability").asText("HABITUAL");
                if (!"HABITUAL".equalsIgnoreCase(durability)) {
                    continue;
                }

                // Check beneficiary: must be SELF
                JsonNode scope = c.path("scope");
                String beneficiary = scope.path("beneficiary").asText("SELF");
                if (!"SELF".equalsIgnoreCase(beneficiary)) {
                    continue;
                }

                // Check quote: must be continuous exact substring of userMessage
                String quote = c.path("evidence").path("quote").asText("").strip();
                if (quote.isBlank() || !userMessage.contains(quote)) {
                    log.warn("Candidate rejected: quote '{}' not found in user message '{}'", quote, userMessage);
                    continue;
                }

                Polarity polarity = "EXCLUDE".equalsIgnoreCase(c.path("polarity").asText()) ? Polarity.EXCLUDE : Polarity.PREFER;
                JsonNode value = c.path("value");
                double confidence = c.path("confidence").asDouble(0.95);

                String prefKey = buildPreferenceKey(type, scope, value, polarity);
                String canonical = buildCanonicalText(type, scope, value, polarity);

                validated.add(new ValidatedCandidate(type, prefKey, scope, value, polarity, canonical, quote, confidence));
            }
            return validated;
        } catch (Exception e) {
            log.warn("Failed to parse extraction output: {}, raw={}", e.getMessage(), rawJson);
            return Collections.emptyList();
        }
    }

    public static String buildPreferenceKey(MemoryType type, JsonNode scope, JsonNode value, Polarity polarity) {
        String category = scope.path("categoryCode").asText("GLOBAL");
        String platform = scope.path("platform").asText("");
        return switch (type) {
            case DELIVERY_PREFERENCE -> "DELIVERY|" + category + "|" + value.path("deliveryMode").asText("AUTO");
            case CATEGORY_PREFERENCE -> "CATEGORY|" + value.path("categoryCode").asText(category);
            case PLATFORM_PREFERENCE -> "PLATFORM|" + (platform.isBlank() ? value.path("platform").asText("GLOBAL") : platform);
            case SPEC_PREFERENCE -> "SPEC|" + category + "|" + value.path("specCode").asText("DEFAULT");
            case PRICE_RANGE -> "PRICE|" + category + "|" + value.path("currency").asText("CNY");
            case EXCLUSION -> "EXCLUSION|" + category + "|" + value.path("excludedItem").asText("NONE");
        };
    }

    public static String buildCanonicalText(MemoryType type, JsonNode scope, JsonNode value, Polarity polarity) {
        String cat = scope.path("categoryCode").asText("");
        String catDesc = cat.isBlank() || "GLOBAL".equals(cat) ? "商品" : cat + "类商品";
        return switch (type) {
            case DELIVERY_PREFERENCE -> "用户购买" + catDesc + "时，偏好" + ("AUTO".equalsIgnoreCase(value.path("deliveryMode").asText()) ? "自动发货" : "人工发货") + "。";
            case CATEGORY_PREFERENCE -> "用户常态偏好购买" + catDesc + "。";
            case PLATFORM_PREFERENCE -> "用户偏好平台/服务区：" + value.path("platform").asText() + "。";
            case SPEC_PREFERENCE -> "用户购买" + catDesc + "时，常选择规格：" + value.path("specName").asText(value.path("specCode").asText()) + "。";
            case PRICE_RANGE -> "用户购买" + catDesc + "时，常态预算范围为：" + value.path("min").asInt(0) + "-" + value.path("max").asInt(0) + value.path("currency").asText("元") + "。";
            case EXCLUSION -> "用户购买" + catDesc + "时，明确排除：" + value.path("excludedItem").asText() + "。";
        };
    }
}
