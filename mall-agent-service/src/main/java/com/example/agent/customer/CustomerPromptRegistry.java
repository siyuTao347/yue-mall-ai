package com.example.agent.customer;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads versioned CustomerAgent prompts and safely inserts untrusted values as XML text. */
@Component
public class CustomerPromptRegistry {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}");
    private final Map<String, String> templates;

    public CustomerPromptRegistry() {
        this.templates = Map.of(
            "master-planner-v3", load("customer/prompts/master-planner-v3.xml.st"),
            "after-sale-react-v3", load("customer/prompts/after-sale-react-v3.xml.st"),
            "response-synthesizer-v3", load("customer/prompts/response-synthesizer-v3.xml.st")
        );
    }

    public String render(String name, Map<String, ?> values) {
        String template = templates.get(name);
        if (template == null) throw new IllegalArgumentException("Unknown CustomerAgent prompt: " + name);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!values.containsKey(key)) throw new IllegalArgumentException("Missing prompt value: " + key);
            matcher.appendReplacement(output, Matcher.quoteReplacement(xmlText(String.valueOf(values.get(key)))));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String load(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load CustomerAgent prompt " + path, e);
        }
    }

    static String xmlText(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
