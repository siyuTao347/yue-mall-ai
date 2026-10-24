package com.example.agent.customer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.example.agent.customer.CustomerJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntentReleaseEvaluatorTest {
    private final CustomerModels models=mock(CustomerModels.class);
    private final CustomerProperties properties=new CustomerProperties();
    private final IntentReleaseEvaluator evaluator=new IntentReleaseEvaluator(models,properties);

    private ObjectNode fallback() {
        return object().put("source","L3_LLM").put("code","OTHER").put("similarity",.82)
            .put("runnerUpScore",.77).put("threshold",.88).put("minMargin",.05)
            .put("fallbackReason","INTENT_LOW_SCORE_OR_MARGIN");
    }
    private ObjectNode accepted(String code) {
        return object().put("source","L2_RAG").put("code",code).put("similarity",.95);
    }
    private String plan(String question,String code,double confidence) {
        return write(object().set("intents",array().add(object().put("code",code)
            .put("confidence",confidence).put("questionFragment",question).set("slots",object()))));
    }

    @Test void lowScoreRequiresRealClassificationBeforeItCanPass() {
        String question="请查订单的结算进度";
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenAnswer(call->{
            // The gold label and L2 candidate never enter the model's context.
            var context=read(call.getArgument(1));
            assertEquals(object(),context.path("pending"));
            assertEquals(question,context.path("history").get(0).path("content").asText());
            assertFalse(context.has("expected"));
            return plan(question,"SETTLEMENT",.96);
        });
        var result=evaluator.evaluate(new IntentReleaseService.Evaluation(question,"SETTLEMENT"),fallback());
        assertTrue(result.path("passed").asBoolean());
        assertEquals("OTHER",result.path("l2Actual").asText());
        assertEquals("SETTLEMENT",result.path("actual").asText());
        assertEquals("L3_LLM",result.path("source").asText());
        verify(models).classify(eq(question),anyString(),any(),any(),any());
        var summary=evaluator.summarize(array().add(result));
        assertEquals(0,summary.path("l2Coverage").asDouble());
        assertEquals(1,summary.path("l3FallbackCount").asInt());
        assertEquals(1,summary.path("finalAccuracy").asDouble());
        assertTrue(summary.path("passed").asBoolean());
    }

    @Test void wrongL2AcceptanceFailsAndIsNotRescuedByL3() {
        var result=evaluator.evaluate(new IntentReleaseService.Evaluation("订单状态","ORDER_STATUS"),accepted("ORDER_TIMELINE"));
        assertFalse(result.path("passed").asBoolean());
        verifyNoInteractions(models);
        var summary=evaluator.summarize(array().add(result));
        assertEquals(1,summary.path("l2WrongAcceptedCount").asInt());
        assertEquals(0,summary.path("l3FallbackCount").asInt());
        assertFalse(summary.path("passed").asBoolean());
    }

    @Test void otherNegativeAlsoNeedsL3AndCanExposeWrongFallbackClassification() {
        String question="解释月相变化";
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenReturn(plan(question,"PLATFORM_RULE",.95));
        var result=evaluator.evaluate(new IntentReleaseService.Evaluation(question,"OTHER"),fallback());
        assertFalse(result.path("passed").asBoolean());
        assertEquals("PLATFORM_RULE",result.path("actual").asText());
        verify(models).classify(eq(question),anyString(),any(),any(),any());
    }

    @Test void modelFailureStaysInAccuracyDenominatorAndBlocksPublication() {
        when(models.classify(anyString(),anyString(),any(),any(),any()))
            .thenThrow(error(504,"AGENT_TIMEOUT","处理超时"));
        var good=evaluator.evaluate(new IntentReleaseService.Evaluation("订单列表","ORDER_SEARCH"),accepted("ORDER_SEARCH"));
        var failed=evaluator.evaluate(new IntentReleaseService.Evaluation("售后条件","AFTER_SALE_GUIDE"),fallback());
        assertEquals("ERROR",failed.path("status").asText());
        assertEquals("AGENT_TIMEOUT",failed.path("errorCode").asText());
        assertTrue(failed.path("actual").isNull());
        var summary=evaluator.summarize(array().add(good).add(failed));
        assertEquals(2,summary.path("totalCount").asInt());
        assertEquals(1,summary.path("evaluatedCount").asInt());
        assertEquals(1,summary.path("errorCount").asInt());
        assertEquals(.5,summary.path("finalAccuracy").asDouble());
        assertFalse(summary.path("passed").asBoolean());
        verify(models,times(2)).classify(anyString(),anyString(),any(),any(),any());
    }

    @Test void invalidGroundingAndMultipleIntentsCannotPassSingleIntentGold() {
        String question="售后申请条件";
        var invalid=read(plan(question,"AFTER_SALE_GUIDE",.95));
        ((ObjectNode)invalid.path("intents").get(0).path("slots")).put("orderNo","SO202610200001");
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenReturn(write(invalid));
        var failure=evaluator.evaluate(new IntentReleaseService.Evaluation(question,"AFTER_SALE_GUIDE"),fallback());
        assertEquals("ERROR",failure.path("status").asText());
        assertFalse(failure.path("passed").asBoolean());

        var multiple=object().set("intents",array()
            .add(read(plan(question,"AFTER_SALE_GUIDE",.95)).path("intents").get(0))
            .add(read(plan(question,"PLATFORM_RULE",.95)).path("intents").get(0)));
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenReturn(write(multiple));
        var result=evaluator.evaluate(new IntentReleaseService.Evaluation(question,"AFTER_SALE_GUIDE"),fallback());
        assertEquals("EVALUATED",result.path("status").asText());
        assertEquals(2,result.path("actualIntents").size());
        assertTrue(result.path("actual").isNull());
        assertFalse(result.path("passed").asBoolean());
    }

    @Test void onlineLowConfidenceRuleAndMockProvenanceArePreserved() {
        String question="查询订单状态";
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenReturn(plan(question,"ORDER_STATUS",.3));
        var result=evaluator.evaluate(new IntentReleaseService.Evaluation(question,"ORDER_STATUS"),fallback());
        assertEquals("OTHER",result.path("actual").asText());
        assertFalse(result.path("passed").asBoolean());
        when(models.mock()).thenReturn(true);
        var mockResult=evaluator.evaluate(new IntentReleaseService.Evaluation("余额提现规则","WITHDRAW_RULE"),fallback());
        assertTrue(mockResult.path("mockL3").asBoolean());
        assertTrue(evaluator.summarize(array().add(mockResult)).path("mockL3").asBoolean());
    }

    @Test void l2AndFinalMetricsHaveExplicitDenominators() {
        var positive=evaluator.evaluate(new IntentReleaseService.Evaluation("订单状态","ORDER_STATUS"),accepted("ORDER_STATUS"));
        String question="天气如何";
        when(models.classify(eq(question),anyString(),any(),any(),any())).thenReturn(plan(question,"OTHER",.99));
        var negative=evaluator.evaluate(new IntentReleaseService.Evaluation(question,"OTHER"),fallback());
        var summary=evaluator.summarize(array().add(positive).add(negative));
        assertEquals(.5,summary.path("l2Coverage").asDouble());
        assertEquals(1,summary.path("businessL2Coverage").asDouble());
        assertEquals(1,summary.path("l2Precision").asDouble());
        assertEquals(1,summary.path("finalAccuracy").asDouble());
        assertFalse(summary.path("businessExecution").asBoolean());
        assertFalse(summary.path("includesL1AndEscalation").asBoolean());
    }
}
