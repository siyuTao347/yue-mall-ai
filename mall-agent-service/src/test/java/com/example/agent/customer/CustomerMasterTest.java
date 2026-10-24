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
    @Test void ca001ReturnsSingleOrderStatusIntent() {
        var output=CustomerMaster.mock("我的 TR202610120001 还没发货，帮我看看现在是什么状态。");
        assertEquals(1,output.path("intents").size());
        assertEquals("ORDER_STATUS",output.path("intents").get(0).path("code").asText());
    }

    @Test void productSlotRepresentationsAreCanonicalizedWithoutMutatingModelOutput() {
        String question="商品 ITEM-5842 的适用区服是什么？";
        for(var value:java.util.List.of(TextNode.valueOf("ITEM-5842"),TextNode.valueOf("prod-5842"),
                TextNode.valueOf(" 5842 "),IntNode.valueOf(5842))) {
            var original=productOutput(question,value);
            String before=write(original);
            var normalized=CustomerMaster.normalizeModelSlots(original);
            assertDoesNotThrow(()->CustomerMaster.validateModel(normalized,question));
            assertEquals("5842",normalized.path("intents").get(0).path("slots").path("itemId").asText());
            assertTrue(normalized.path("intents").get(0).path("slots").path("itemId").isTextual());
            assertEquals(before,write(original));
        }
    }

    @Test void productNormalizationNeverAcceptsInventedPartialOrUnrelatedNumbers() {
        String question="商品 ITEM-5842 的适用区服是什么？";
        for(var value:java.util.List.of(TextNode.valueOf("ITEM-9999"),TextNode.valueOf("584"),
                TextNode.valueOf("null"),TextNode.valueOf("5842abc"),DoubleNode.valueOf(5842.0),
                TextNode.valueOf(""),IntNode.valueOf(-5842))) {
            var normalized=CustomerMaster.normalizeModelSlots(productOutput(question,value));
            assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(normalized,question));
        }
        String amount="如果账户余额有 5842 元，能全部提现吗？";
        var ungrounded=CustomerMaster.normalizeModelSlots(productOutput(amount,IntNode.valueOf(5842)));
        assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(ungrounded,amount));
        String longer="商品 ITEM-58420 有货吗？";
        var partial=CustomerMaster.normalizeModelSlots(productOutput(longer,TextNode.valueOf("ITEM-5842")));
        assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(partial,longer));
    }

    @Test void productContextMustBeRecentAndNullIsValidForGeneralQuestions() {
        String question="这个还有库存吗？";
        var output=CustomerMaster.normalizeModelSlots(productOutput(question,TextNode.valueOf("ITEM-5842")));
        var context=object().put("turnNo",4).put("activeFocusTurn",3);
        context.set("activeFocus",object().put("itemId","5842"));
        assertDoesNotThrow(()->CustomerMaster.validateModel(output,question,context));
        context.put("turnNo",7);
        assertThrows(RuntimeException.class,()->CustomerMaster.validateModel(output,question,context));
        String general="账户余额提现最低需要多少金额？";
        var noItem=productOutput(general,NullNode.getInstance());
        ((ObjectNode)noItem.path("intents").get(0)).put("code","WITHDRAW_RULE");
        assertDoesNotThrow(()->CustomerMaster.validateModel(CustomerMaster.normalizeModelSlots(noItem),general));
    }

    @Test void masterPromptCoversRuntimeCatalogAndDefinesCanonicalProductSlots() {
        String prompt=new CustomerPromptRegistry().render("master-planner-v3",java.util.Map.of(
            "maxIntents",2,"rollingSummary","","activeEntitiesJson","{}","pendingIntentsJson","[]",
            "recentMessagesJson","[]","userQuestion","商品查询"));
        for(String code:IntentCatalog.ROUTES.keySet())assertTrue(prompt.contains(code),code);
        assertTrue(prompt.contains("不保留 ITEM-/PROD- 前缀"));
        assertTrue(prompt.contains("JSON null"));
    }

    private static ObjectNode productOutput(String question,com.fasterxml.jackson.databind.JsonNode value) {
        var slots=object().putNull("orderNo").putNull("disputeNo");
        slots.set("itemId",value);
        var intent=object().put("code","PRE_SALE_PRODUCT").put("confidence",.95)
            .put("questionFragment",question).put("rewrittenQuestion",question);
        intent.set("slots",slots);
        return object().set("intents",array().add(intent));
    }
}
