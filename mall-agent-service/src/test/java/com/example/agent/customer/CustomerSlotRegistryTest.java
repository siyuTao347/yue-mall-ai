package com.example.agent.customer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomerSlotRegistryTest {

    @Test
    void testExtractSlots_withNormalOrderAndDispute() {
        String text = "请帮我查一下订单 T202610310001 和售后单 DP202610310002 的情况";
        Map<String, String> slots = CustomerSlotRegistry.extractSlots(text);

        assertEquals("T202610310001", slots.get("orderNo"));
        assertEquals("DP202610310002", slots.get("disputeNo"));
    }

    @Test
    void testExtractSlots_masksEmbeddedOrderInPaymentAttempt() {
        // 关键用例：PA_T202610310001_01 中内嵌了订单号，但用户并未明确提供独立订单号
        String text = "这笔支付尝试 PA_T202610310001_01 显示扣款失败了";
        Map<String, String> slots = CustomerSlotRegistry.extractSlots(text);

        assertEquals("PA_T202610310001_01", slots.get("attemptNo"));
        assertNull(slots.get("orderNo"), "内嵌于 PA_ 编号内的订单号片段不应被误抽取为独立 orderNo");
    }

    @Test
    void testExtractSlots_withExplicitOrderAndAttempt() {
        String text = "订单 T202610310001 的支付尝试 PA_T202610310001_01 请核查";
        Map<String, String> slots = CustomerSlotRegistry.extractSlots(text);

        assertEquals("T202610310001", slots.get("orderNo"));
        assertEquals("PA_T202610310001_01", slots.get("attemptNo"));
    }

    @Test
    void testExtractSlots_withMoneyCaseNo() {
        String text = "请帮我查询资金案件 MC0123456789ABCDEF0123456789ABCDEF 的处理进度";
        Map<String, String> slots = CustomerSlotRegistry.extractSlots(text);

        assertEquals("MC0123456789ABCDEF0123456789ABCDEF", slots.get("moneyCaseNo"));
    }

    @Test
    void testPromptMissing_andDisplayName() {
        assertEquals("订单号", CustomerSlotRegistry.promptMissing("orderNo"));
        assertEquals("售后单号", CustomerSlotRegistry.promptMissing("disputeNo"));
        assertEquals("商品编号", CustomerSlotRegistry.promptMissing("itemId"));
        assertEquals("资金案件编号", CustomerSlotRegistry.promptMissing("moneyCaseNo"));

        assertEquals("moneyCases", CustomerSlotRegistry.collection("moneyCaseNo"));
        assertEquals("orders", CustomerSlotRegistry.collection("orderNo"));
    }
}

