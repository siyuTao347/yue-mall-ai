package com.example.agent.customer;

import api.agent.AgentActor;
import api.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.agent.customer.CustomerJson.*;

/**
 * Stateless classification evaluation. Deliberately has no Runner, Store, business
 * tools or memory facade: even HUMAN_HELP / MEMORY_FORGET only return intent labels.
 * Context is an explicit test fixture, not a claim of completed business operations.
 */
@RestController
@RequestMapping("/api/admin/agent/intent-evaluation")
public class CustomerIntentEvaluationController {
    private final CustomerMaster master;
    private final CustomerModels models;
    private final CustomerProperties properties;
    private static final String IMPLEMENTATION_HASH = implementationHash();

    public CustomerIntentEvaluationController(CustomerMaster master, CustomerModels models,
                                              CustomerProperties properties) {
        this.master = master;
        this.models = models;
        this.properties = properties;
    }

    public record RecognizeRequest(@NotBlank @Size(max = 2000) String content,
                                   JsonNode context, JsonNode history) {}

    @GetMapping
    public ApiResponse<ObjectNode> capabilities(AgentActor actor) {
        requireAdmin(actor);
        return ApiResponse.success(metadata());
    }

    @PostMapping("/recognize")
    public ApiResponse<ObjectNode> recognize(AgentActor actor, @Valid @RequestBody RecognizeRequest request) {
        requireAdmin(actor);
        if (request.content() == null || request.content().isBlank()
                || request.content().length() > 2000 || request.content().indexOf('\0') >= 0) {
            throw error(400, "AGENT_MESSAGE_INVALID", "消息须为 1–2000 个字符");
        }
        ObjectNode context = context(request.context());
        String question = redact(request.content());
        ArrayNode history = history(request.history(), question);
        String classificationId = id("IE");
        AtomicInteger input = new AtomicInteger(), output = new AtomicInteger();
        CustomerBudget budget = new CustomerBudget(properties.getRunTimeoutMs());
        long started = System.nanoTime();
        ObjectNode classified = budget.call(properties.getRunTimeoutMs(),
            () -> master.recognize(question, classificationId, context, history, budget, input, output));
        ObjectNode result = metadata()
            .put("classificationId", classificationId)
            .put("inputTokens", input.get()).put("outputTokens", output.get())
            .put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        result.set("master", classified);
        return ApiResponse.success(result);
    }

    private ObjectNode metadata() {
        return object().put("contractVersion", 1).put("mode", "RECOGNITION_ONLY")
            .put("businessExecution", false).put("contextMode", "EXPLICIT_FIXTURE")
            .put("provider", properties.getProvider()).put("masterModel", properties.getMasterModel())
            .put("mock", models.mock()).put("ragEnabled", properties.isRagEnabled())
            .put("implementationSha256", IMPLEMENTATION_HASH);
    }

    private static void requireAdmin(AgentActor actor) {
        if (actor == null || !"ADMIN".equalsIgnoreCase(actor.role())) {
            throw error(403, "AGENT_PERMISSION_DENIED", "仅管理员可运行意图评估");
        }
    }

    private static ObjectNode context(JsonNode supplied) {
        if (supplied == null || supplied.isNull()) return object();
        if (!supplied.isObject() || supplied.toString().length() > 32000) {
            throw error(400, "INTENT_EVAL_CONTEXT_INVALID", "上下文须为不超过 32000 字符的 JSON 对象");
        }
        JsonNode pending = supplied.path("pendingIntents");
        if (!pending.isMissingNode()) {
            if (!pending.isArray() || pending.size() > 8) {
                throw error(400, "INTENT_EVAL_CONTEXT_INVALID", "pendingIntents 须为最多 8 项的数组");
            }
            for (JsonNode task : pending) {
                if (!task.isObject() || !IntentCatalog.ROUTES.containsKey(task.path("code").asText())
                        || task.path("intentInstanceId").asText().isBlank()
                        || !task.path("slots").isObject() || !task.path("slotSources").isObject()
                        || !task.path("requiredSlots").isArray() || !task.path("missingSlots").isArray()) {
                    throw error(400, "INTENT_EVAL_CONTEXT_INVALID", "待澄清意图结构不完整");
                }
            }
        }
        return supplied.deepCopy();
    }

    private static ArrayNode history(JsonNode supplied, String question) {
        ArrayNode history = array();
        if (supplied != null && !supplied.isNull()) {
            if (!supplied.isArray() || supplied.size() > 8) {
                throw error(400, "INTENT_EVAL_HISTORY_INVALID", "history 须为最多 8 条历史消息");
            }
            for (JsonNode message : supplied) {
                String role = message.path("role").asText().toUpperCase(Locale.ROOT);
                JsonNode content = message.path("content");
                if (!Set.of("USER", "ASSISTANT").contains(role) || !content.isTextual()
                        || content.asText().isBlank() || content.asText().length() > 2000) {
                    throw error(400, "INTENT_EVAL_HISTORY_INVALID", "历史消息须包含合法 role 和 content");
                }
                history.add(object().put("role", role).put("content", redact(content.asText())));
            }
        }
        // CustomerRunner reads history after storing the current USER message.
        history.add(object().put("role", "USER").put("content", question));
        while (history.size() > 8) history.remove(0);
        return history;
    }

    private static String implementationHash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Class<?> type : List.of(CustomerIntentEvaluationController.class, CustomerMaster.class,
                    IntentReleaseService.class, CustomerModels.class, CustomerContextWindowBuilder.class,
                    CustomerPromptRegistry.class, CustomerProperties.class, OnlineEmbedding.class)) {
                try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                    if (bytes == null) throw new IOException("Missing class bytes");
                    digest.update(bytes.readAllBytes());
                }
            }
            try (var prompt = CustomerPromptRegistry.class.getResourceAsStream("/customer/prompts/master-planner-v3.xml.st")) {
                if (prompt == null) throw new IOException("Missing master prompt");
                digest.update(prompt.readAllBytes());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint intent evaluator", e);
        }
    }
}
