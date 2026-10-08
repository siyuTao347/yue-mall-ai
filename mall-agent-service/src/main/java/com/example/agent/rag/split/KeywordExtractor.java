package com.example.agent.rag.split;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.example.agent.rag.TextTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词提取：用于关键词检索降级路径。
 * <p>标题命中权重 ×5，正文命中 ×1；中文按 2-gram，英文/型号按词。输出 JSON 便于存 json 列。</p>
 */
public final class KeywordExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_ASCII_WORD_LENGTH = 2;

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "及", "或", "我们", "你们", "他们", "这个", "那个", "一个",
            "可以", "需要", "进行", "以及", "但是", "如果", "因为", "所以", "并且", "通过", "对于", "根据",
            "the", "and", "for", "with", "that", "this", "you", "are", "from", "not", "was", "has");

    private KeywordExtractor() {
    }

    public static String extractJson(String titlePath, String content, int limit) {
        Map<String, Double> weights = extract(titlePath, content);
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : entries) {
            if (result.size() >= limit) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    static Map<String, Double> extract(String titlePath, String content) {
        Map<String, Double> weights = new LinkedHashMap<>();
        collect(titlePath == null ? "" : titlePath, 5.0, weights);
        collect(content == null ? "" : content, 1.0, weights);
        return weights;
    }

    private static void collect(String text, double weight, Map<String, Double> weights) {
        for (String token : TextTokenizer.tokens(text)) {
            if (!STOPWORDS.contains(token)) {
                weights.merge(token, weight, Double::sum);
            }
        }
    }

}
