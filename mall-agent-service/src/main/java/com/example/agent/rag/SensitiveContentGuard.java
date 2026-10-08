package com.example.agent.rag;

import com.example.agent.config.RagProperties;
import com.example.agent.exception.RagApiException;
import com.example.agent.web.ErrorCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识文档敏感信息校验：禁止把卡密明文、身份证、银行卡号、口令等写入知识库。
 * <p>策略由 {@code rag.admin.sensitive-action} 控制：</p>
 * <ul>
 *   <li>{@code REJECT}（默认）：命中即拒绝保存；</li>
 *   <li>{@code MASK}：命中片段替换为 {@code ***} 后放行，用于确实需要保留上下文的文档。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SensitiveContentGuard {

    private record Rule(String name, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("身份证号", Pattern.compile("\\b\\d{17}[\\dXx]\\b")),
            new Rule("银行卡号", Pattern.compile("\\b\\d{16,19}\\b")),
            new Rule("口令/密码", Pattern.compile("(密码|口令|password|passwd)\\s*[:=：]\\s*\\S{6,}")),
            new Rule("卡密/密钥", Pattern.compile("(卡密|密钥|secret|api[-_]?key)\\s*[:=：]\\s*\\S{8,}")));

    private final RagProperties ragProperties;

    /**
     * 校验（必要时脱敏）文档内容。
     *
     * @return 通过校验的内容；MASK 策略下为脱敏后的内容
     */
    public String sanitize(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        boolean mask = "MASK".equalsIgnoreCase(ragProperties.getAdmin().getSensitiveAction());
        String result = content;
        for (Rule rule : RULES) {
            if (!rule.pattern().matcher(result).find()) {
                continue;
            }
            if (!mask) {
                throw RagApiException.badRequest(ErrorCodes.RAG_DOC_SENSITIVE_CONTENT,
                        "文档内容命中敏感信息规则（" + rule.name() + "），请脱敏后再保存");
            }
            result = rule.pattern().matcher(result).replaceAll("***");
        }
        return result;
    }
}
