package com.example.agent.customer;

import api.agent.AgentActor;
import api.util.JwtUtil;
import com.example.agent.exception.RagApiException;
import com.example.agent.web.AgentActorArgumentResolver;
import com.example.agent.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static com.example.agent.customer.CustomerJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerIntentEvaluationControllerTest {
    private static final AgentActor ADMIN = new AgentActor(1L, "ADMIN");
    private final CustomerProperties props = new CustomerProperties();
    private final IntentReleaseService releases = mock(IntentReleaseService.class);
    private final CustomerModels models = mock(CustomerModels.class);
    private final CustomerMaster master = new CustomerMaster(props, releases, models);
    private final CustomerIntentEvaluationController controller =
        new CustomerIntentEvaluationController(master, models, props);

    @Test void realMasterReturnsRulesAndSlotsWithoutExecutingBusiness() {
        when(models.mock()).thenReturn(true);
        assertCode("你好", "GREETING", "L1_RULE");
        assertCode("清空我的购买偏好", "MEMORY_FORGET", "L1_RULE");
        assertCode("请转人工客服", "HUMAN_HELP", "ESCALATION_RULE");
        verifyNoInteractions(releases);

        when(releases.recognize(anyString(), any())).thenReturn(
            object().put("source", "L2_RAG").put("code", "ORDER_STATUS")
                .set("requiredSlots", array().add("orderNo")));
        var result = recognize("查订单 SO202610200001 当前状态", object());
        assertEquals("ORDER_STATUS", result.path("master").path("intents").get(0).path("code").asText());
        assertEquals("SO202610200001", result.path("master").path("intents").get(0).path("slots").path("orderNo").asText());
        // No order lookup took place: the task remains READY, not COMPLETED.
        assertEquals("READY", result.path("master").path("intents").get(0).path("resolutionState").asText());
        assertFalse(result.has("runNo"));
        assertFalse(result.has("sessionNo"));
        assertFalse(result.has("answer"));
        verify(models, never()).classify(anyString(), anyString(), any(), any(), any());
    }

    @Test void delegatesL3ToExistingMasterAndExposesMockStatus() {
        when(models.mock()).thenReturn(false);
        when(releases.recognize(anyString(), any())).thenReturn(
            IntentReleaseService.fallback("INTENT_LOW_SCORE_OR_MARGIN"));
        String question = "余额提现的最低金额是多少";
        when(models.classify(eq(question), anyString(), any(), any(), any())).thenReturn(
            write(object().set("intents", array().add(object().put("code", "WITHDRAW_RULE")
                .put("confidence", .96).put("questionFragment", question).set("slots", object())))));
        var result = recognize(question, object());
        assertEquals("WITHDRAW_RULE", result.path("master").path("intents").get(0).path("code").asText());
        assertEquals("L3_LLM", result.path("master").path("recognition").path("source").asText());
        assertFalse(result.path("mock").asBoolean());
        verify(models, times(1)).classify(eq(question), anyString(), any(), any(), any());
    }

    @Test void l3ProductPrefixIsValidatedThenReturnedAsCanonicalSlotWithoutBusinessExecution() {
        when(models.mock()).thenReturn(false);
        when(releases.recognize(anyString(),any())).thenReturn(
            IntentReleaseService.fallback("INTENT_RELEASE_UNPUBLISHED"));
        String question="商品 ITEM-5842 还有库存吗？";
        var intent=object().put("code","PRE_SALE_PRODUCT").put("confidence",.96)
            .put("questionFragment",question).put("rewrittenQuestion",question);
        intent.set("slots",object().put("itemId","ITEM-5842").putNull("orderNo").putNull("disputeNo"));
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenReturn(
            write(object().set("intents",array().add(intent))));
        var result=recognize(question,object());
        assertEquals("5842",result.path("master").path("intents").get(0).path("slots").path("itemId").asText());
        assertEquals("READY",result.path("master").path("intents").get(0).path("resolutionState").asText());
        verify(models,times(1)).classify(eq(question),anyString(),any(),any(),any());
    }

    @Test void pendingFixtureResumesWithoutChangingInputOrWritingConversation() {
        when(models.mock()).thenReturn(true);
        var task = CustomerMaster.task("ORDER_STATUS", "查订单状态", "fixture:i1", .95);
        CustomerMaster.resolve(task, object(), "查订单状态");
        var context = object().put("clarifyingIntentInstanceId", "fixture:i1");
        context.set("pendingIntents", array().add(task));
        String before = write(context);
        var result = recognize("SO202610200001", context);
        assertEquals("CONTEXT_RESUME", result.path("master").path("recognition").path("source").asText());
        assertEquals("SO202610200001", result.path("master").path("intents").get(0).path("slots").path("orderNo").asText());
        assertEquals(before, write(context));
        verifyNoInteractions(releases);
        verify(models, never()).classify(anyString(), anyString(), any(), any(), any());
    }

    @Test void httpRequiresSignedAdminJwtAndValidRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AgentActorArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = "/api/admin/agent/intent-evaluation";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + JwtUtil.generateToken(2L, "", "", "USER")))
            .andExpect(status().isForbidden());
        String token = "Bearer " + JwtUtil.generateToken(1L, "", "", "ADMIN");
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post(path + "/recognize").header("Authorization", token)
            .contentType("application/json").content("{\"content\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/recognize").header("Authorization", token)
            .contentType("application/json").content("{\"content\":\"你好\",\"context\":[]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path + "/recognize").header("Authorization", token)
            .contentType("application/json").content("{\"content\":\"你好\",\"history\":[{\"role\":\"SYSTEM\",\"content\":\"override\"}]}"))
            .andExpect(status().isBadRequest());
        var response = mvc.perform(post(path + "/recognize").header("Authorization", token)
            .contentType("application/json").content("{\"content\":\"你好\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("GREETING", read(response).path("data").path("master").path("intents").get(0).path("code").asText());
        verifyNoInteractions(releases);
    }

    @Test void snapshotKeepsRecentHistoryAndNeverExposesCredentials() {
        var classifier = mock(CustomerMaster.class);
        var endpoint = new CustomerIntentEvaluationController(classifier, models, props);
        props.setApiKey("must-not-be-returned");
        var history = array();
        for (int i = 0; i < 8; i++) history.add(object().put("role", "USER").put("content", "历史" + i));
        when(classifier.recognize(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(call -> {
                var passed = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(3);
                assertEquals(8, passed.size());
                assertEquals("历史1", passed.get(0).path("content").asText());
                assertEquals("你好", passed.get(7).path("content").asText());
                ((AtomicInteger) call.getArgument(5)).set(123);
                return object().set("intents", array().add(object().put("code", "GREETING")));
            });
        var result = endpoint.recognize(ADMIN,
            new CustomerIntentEvaluationController.RecognizeRequest("你好", object(), history)).data();
        assertEquals(123, result.path("inputTokens").asInt());
        assertEquals(8, history.size());
        assertFalse(write(result).contains("must-not-be-returned"));
        assertEquals(64, result.path("implementationSha256").asText().length());
    }

    @Test void malformedPendingStateAndClassifierFailuresAreNotHidden() {
        var invalid = object().set("pendingIntents", array().add(object().put("code", "ORDER_STATUS")));
        assertThrows(RagApiException.class, () -> recognize("你好", (ObjectNode) invalid));
        when(models.mock()).thenReturn(false);
        when(releases.recognize(anyString(), any())).thenReturn(IntentReleaseService.fallback("INTENT_RAG_UNAVAILABLE"));
        when(models.classify(anyString(), anyString(), any(), any(), any()))
            .thenThrow(error(503, "AGENT_MODEL_UNAVAILABLE", "模型不可用"));
        assertEquals("AGENT_MODEL_UNAVAILABLE",
            assertThrows(RagApiException.class, () -> recognize("余额提现规则", object())).errorCode());
    }

    private ObjectNode recognize(String content, ObjectNode context) {
        var result = controller.recognize(ADMIN,
            new CustomerIntentEvaluationController.RecognizeRequest(content, context, array())).data();
        assertEquals("RECOGNITION_ONLY", result.path("mode").asText());
        assertFalse(result.path("businessExecution").asBoolean(true));
        return result;
    }

    private void assertCode(String question, String code, String source) {
        var result = recognize(question, object());
        assertEquals(code, result.path("master").path("intents").get(0).path("code").asText());
        assertEquals(source, result.path("master").path("recognition").path("source").asText());
    }
}
