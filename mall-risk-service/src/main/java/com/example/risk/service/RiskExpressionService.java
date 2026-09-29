package com.example.risk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RiskExpressionService {
    private static final Set<String> OPERATORS = Set.of(
            "EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY", "NOT_EMPTY"
    );
    private final ObjectMapper objectMapper;

    public RiskExpressionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(String expressionJson) {
        JsonNode root = read(expressionJson);
        String combinator = requireCombinator(root);
        JsonNode conditions = root.get(combinator);
        if (!conditions.isArray() || conditions.isEmpty() || conditions.size() > 50) {
            throw new IllegalArgumentException("规则条件数量必须在 1 到 50 之间");
        }
        for (JsonNode condition : conditions) {
            validateCondition(condition);
        }
    }

    public Evaluation evaluate(String expressionJson, Map<String, Object> metrics) {
        JsonNode root = read(expressionJson);
        String combinator = requireCombinator(root);
        boolean all = "all".equals(combinator);
        boolean matched = all;
        Set<String> missing = new HashSet<>();
        Map<String, Object> actualValues = new LinkedHashMap<>();
        for (JsonNode condition : root.get(combinator)) {
            String metric = requiredText(condition, "metric");
            String operator = requiredText(condition, "operator");
            Object actual = metrics.get(metric);
            actualValues.put(metric, actual);
            if (actual == null) {
                missing.add(metric);
                matched = false;
                continue;
            }
            boolean result = compare(actual, operator, condition.get("value"));
            if (all) {
                matched = matched && result;
            } else {
                matched = matched || result;
            }
        }
        return new Evaluation(matched, missing, actualValues);
    }

    private void validateCondition(JsonNode condition) {
        if (condition == null || !condition.isObject() || condition.size() < 3) {
            throw new IllegalArgumentException("规则条件必须是包含 metric、operator、value 的对象");
        }
        String metric = requiredText(condition, "metric");
        String operator = requiredText(condition, "operator");
        if (!RiskMetricCodes.ALLOWED.contains(metric)) {
            throw new IllegalArgumentException("未知风控指标: " + metric);
        }
        if (!OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("不支持的风控操作符: " + operator);
        }
        JsonNode value = condition.get("value");
        if ("BETWEEN".equals(operator)) {
            if (!value.isArray() || value.size() != 2 || !value.get(0).isNumber() || !value.get(1).isNumber()) {
                throw new IllegalArgumentException("BETWEEN 条件必须是两个数字");
            }
            return;
        }
        if (!value.isNumber() && !value.isTextual() && !value.isBoolean() && !value.isArray()) {
            throw new IllegalArgumentException("规则阈值只支持数字、字符串、布尔值和数组");
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (!item.isNumber() && !item.isTextual() && !item.isBoolean()) {
                    throw new IllegalArgumentException("规则阈值数组只支持标量");
                }
            }
        }
    }

    private boolean compare(Object actual, String operator, JsonNode expected) {
        return switch (operator) {
            case "EQ" -> equalsValue(actual, expected);
            case "NE" -> !equalsValue(actual, expected);
            case "GT", "GTE", "LT", "LTE" -> compareNumber(actual, expected, operator);
            case "IN" -> in(actual, expected, true);
            case "NOT_IN" -> in(actual, expected, false);
            case "BETWEEN" -> between(actual, expected);
            case "IS_EMPTY" -> isEmpty(actual);
            case "NOT_EMPTY" -> !isEmpty(actual);
            default -> false;
        };
    }

    private boolean equalsValue(Object actual, JsonNode expected) {
        if (expected.isNumber() && actual instanceof Number number) {
            return toDecimal(number).compareTo(new BigDecimal(expected.asText())) == 0;
        }
        if (expected.isBoolean() && actual instanceof Boolean bool) {
            return expected.asBoolean() == bool;
        }
        return expected.asText().equals(String.valueOf(actual));
    }

    private boolean compareNumber(Object actual, JsonNode expected, String operator) {
        if (!(actual instanceof Number number) || !expected.isNumber()) {
            return false;
        }
        int result = toDecimal(number).compareTo(new BigDecimal(expected.asText()));
        return switch (operator) {
            case "GT" -> result > 0;
            case "GTE" -> result >= 0;
            case "LT" -> result < 0;
            default -> result <= 0;
        };
    }

    private boolean in(Object actual, JsonNode expected, boolean expectedIn) {
        if (!expected.isArray()) {
            return equalsValue(actual, expected) == expectedIn;
        }
        List<String> values = new ArrayList<>();
        expected.forEach(item -> values.add(item.asText()));
        if (actual instanceof Collection<?> collection) {
            boolean intersects = collection.stream().map(String::valueOf).anyMatch(values::contains);
            return intersects == expectedIn;
        }
        return values.contains(String.valueOf(actual)) == expectedIn;
    }

    private boolean between(Object actual, JsonNode expected) {
        if (!(actual instanceof Number number) || !expected.isArray() || expected.size() != 2) {
            return false;
        }
        BigDecimal value = toDecimal(number);
        return value.compareTo(new BigDecimal(expected.get(0).asText())) >= 0
                && value.compareTo(new BigDecimal(expected.get(1).asText())) <= 0;
    }

    private boolean isEmpty(Object actual) {
        if (actual == null) {
            return true;
        }
        if (actual instanceof String text) {
            return text.isBlank();
        }
        if (actual instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    private BigDecimal toDecimal(Number number) {
        return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    }

    private String requireCombinator(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("规则表达式必须且只能包含一层 all 或 any");
        }
        boolean all = root.has("all");
        boolean any = root.has("any");
        if (root.size() != 1 || all == any) {
            throw new IllegalArgumentException("规则表达式必须且只能包含一层 all 或 any");
        }
        return all ? "all" : "any";
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("规则条件缺少 " + field);
        }
        return value.asText();
    }

    private JsonNode read(String expressionJson) {
        try {
            JsonNode node = objectMapper.readTree(expressionJson);
            if (node == null) {
                throw new IllegalArgumentException("规则表达式不能为空");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("规则表达式不是合法 JSON", e);
        }
    }

    public record Evaluation(boolean matched, Set<String> missingMetrics,
                             Map<String, Object> actualValues) {
    }
}
