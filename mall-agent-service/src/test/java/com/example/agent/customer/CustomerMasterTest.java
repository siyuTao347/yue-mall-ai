package com.example.agent.customer;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.agent.customer.CustomerJson.*;

class CustomerMasterTest {
    @Test void l1MatchesWholeNormalizedUtteranceOnly(){
        for(String q:new String[]{"你好","Hello","ＨＥＬＬＯ！"," 下午好 ","hi"})assertEquals("GREETING",CustomerMaster.l1(q,20));
        assertEquals("BOT_IDENTITY",CustomerMaster.l1("你是谁？",20));
        for(String q:new String[]{"你好，我要退款","Hello，我的订单还没到","你是谁，给我转人工","退款","订单","hellos"})assertNull(CustomerMaster.l1(q,20));
        assertNotNull(CustomerMaster.l1("你好"+"!".repeat(18),20));assertNull(CustomerMaster.l1("你好"+"!".repeat(19),20));
    }
    @Test void instancesNeverBorrowAnotherOrdersSlots(){
        ObjectNode a=CustomerMaster.task("ORDER_STATUS","查 TR202610120001 交付","r:i1",.95),b=CustomerMaster.task("SETTLEMENT","查 TR202610120002 结算","r:i2",.95);
        CustomerMaster.resolve(a,object(),a.path("questionFragment").asText());CustomerMaster.resolve(b,object(),b.path("questionFragment").asText());
        assertEquals("TR202610120001",a.path("slots").path("orderNo").asText());assertEquals("TR202610120002",b.path("slots").path("orderNo").asText());
        ObjectNode vague=CustomerMaster.task("ORDER_STATUS","那个订单","r:i3",.95);ObjectNode context=object();context.set("confirmedEntities",object().set("orderNo",array().add("TR202610120001").add("TR202610120002")));
        CustomerMaster.resolve(vague,context,"那个订单");assertEquals("NEED_CLARIFICATION",vague.path("resolutionState").asText());assertFalse(vague.path("slots").has("orderNo"));
    }
    @Test void clarificationResumesOnlyTargetInstance(){
        ObjectNode a=CustomerMaster.task("ORDER_STATUS","我的订单在哪","r:i1",.95),b=CustomerMaster.task("SETTLEMENT","什么时候结算","r:i2",.95);
        CustomerMaster.resolve(a,object(),"");CustomerMaster.resolve(b,object(),"");ObjectNode context=object().put("clarifyingIntentInstanceId","r:i2");context.set("pendingIntents",array().add(a).add(b));
        var resumed=CustomerMaster.resume("TR202610120002",context);assertNotNull(resumed);assertEquals("r:i2",resumed.path("intents").get(0).path("intentInstanceId").asText());
        assertFalse(a.path("slots").has("orderNo"));assertFalse(b.path("slots").has("orderNo"));
    }
    @Test void rejectsInventedSlotsAndUnknownCodes(){
        ObjectNode good=CustomerMaster.mock("我的订单在哪里");assertDoesNotThrow(()->CustomerMaster.validateModel(good,"我的订单在哪里"));
        ((ObjectNode)good.path("intents").get(0).path("slots")).put("orderNo","TR99999999");assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(good,"我的订单在哪里"));
        ((ObjectNode)good.path("intents").get(0)).put("code","DELETE_ORDER");assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(good,"我的订单在哪里"));
    }
    @Test void mockSeparatesTwoOrdersAndDoesNotFabricateUnknownIntent(){var json=CustomerMaster.mock("查订单 TR202610120001 的进度，再查订单 TR202610120002 的结算");assertEquals(2,json.path("intents").size());assertEquals("ORDER_STATUS",json.path("intents").get(0).path("code").asText());assertEquals("SETTLEMENT",json.path("intents").get(1).path("code").asText());}
    @Test void exactThresholdAndMarginBoundaries(){assertFalse(IntentReleaseService.accept(.88,.7,.88,.05));assertTrue(IntentReleaseService.accept(.91,.86,.88,.05));assertFalse(IntentReleaseService.accept(.91,.90,.88,.05));assertFalse(IntentReleaseService.accept(Double.NaN,.7,.88,.05));}
    @Test void rejectsInvalidQueryVectors(){assertThrows(IllegalArgumentException.class,()->OnlineEmbedding.validate(new float[]{0,0},2));assertThrows(IllegalArgumentException.class,()->OnlineEmbedding.validate(new float[]{Float.NaN,1},2));assertThrows(IllegalArgumentException.class,()->OnlineEmbedding.validate(new float[]{1},2));}

    @Test void shortFollowupOnlyUsesUniqueConfirmedEntity() {
        var context=object().put("lastIntent","ORDER_STATUS");
        context.set("confirmedEntities",object().set("orderNo",array().add(object().put("value","TR202610120001").put("confirmed",true))));
        var classification=CustomerMaster.mock("那个呢",context);
        assertEquals("ORDER_STATUS",classification.path("intents").get(0).path("code").asText());
        var task=CustomerMaster.task("ORDER_STATUS","那个呢","r:i1",.95);CustomerMaster.resolve(task,context,"那个呢");
        assertEquals("TR202610120001",task.path("slots").path("orderNo").asText());
        assertEquals("CONFIRMED_CONTEXT",task.path("slotSources").path("orderNo").asText());
    }
    @Test void rewriteCannotIntroduceAnOrderNumber() {
        var output=CustomerMaster.mock("我的订单在哪");
        ((ObjectNode)output.path("intents").get(0)).put("rewrittenQuestion","查订单 TR202610120001");
        assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(output,"我的订单在哪"));
    }
}
