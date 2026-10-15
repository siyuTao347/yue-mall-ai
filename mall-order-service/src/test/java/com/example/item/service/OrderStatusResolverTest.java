package com.example.item.service;

import com.example.item.dto.UserOrderListDTO;
import com.example.item.entity.TradeOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

class OrderStatusResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolveStatusGroupMatchesDocumentSpecifications() {
        TradeOrder order = new TradeOrder();

        order.setOrderStatus("WAIT_PAY");
        order.setDisputeStatus("NONE");
        Assertions.assertEquals("WAIT_PAY", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("CREATE_PENDING");
        Assertions.assertEquals("PROCESSING", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("PAID");
        Assertions.assertEquals("WAIT_DELIVERY", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("DELIVERED");
        Assertions.assertEquals("WAIT_CONFIRM", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("CONFIRMED");
        Assertions.assertEquals("COMPLETED", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("CANCELLED");
        Assertions.assertEquals("CLOSED", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("PAID");
        order.setDisputeStatus("ARBITRATING");
        Assertions.assertEquals("AFTER_SALE", OrderStatusResolver.resolveStatusGroup(order));

        order.setOrderStatus("REFUNDED");
        order.setDisputeStatus("NONE");
        Assertions.assertEquals("AFTER_SALE", OrderStatusResolver.resolveStatusGroup(order));
    }

    @Test
    void toUserOrderDTOBuildsFullContext() {
        TradeOrder order = new TradeOrder();
        order.setId(101L);
        order.setOrderNo("TR202610150001");
        order.setBuyerId(10L);
        order.setSellerId(20L);
        order.setMerchantId(30L);
        order.setItemId(1001L);
        order.setQuantity(2);
        order.setOrderAmount(new BigDecimal("198.00"));
        order.setOrderStatus("WAIT_PAY");
        order.setPayStatus("INIT");
        order.setDisputeStatus("NONE");
        order.setPayDeadline(LocalDateTime.now().plusMinutes(15));
        order.setItemSnapshot("{\"itemId\":1001,\"itemName\":\"AK-47 火神\",\"price\":99.00,\"deliveryMode\":\"AUTO_CARD\"}");
        order.setCreatedTime(LocalDateTime.now().minusMinutes(5));

        UserOrderListDTO dto = OrderStatusResolver.toUserOrderDTO(order, "BUYER", 10L, objectMapper, LocalDateTime.now());

        Assertions.assertNotNull(dto);
        Assertions.assertEquals("TR202610150001", dto.getOrderNo());
        Assertions.assertEquals("AK-47 火神", dto.getItem().getName());
        Assertions.assertEquals("AUTO_CARD", dto.getItem().getDeliveryMode());
        Assertions.assertEquals("99.00", dto.getUnitPrice());
        Assertions.assertEquals("198.00", dto.getOrderAmount());
        Assertions.assertEquals("WAIT_PAY", dto.getStatusGroup());
        Assertions.assertTrue(dto.getAllowedActions().contains("PAY"));
        Assertions.assertTrue(dto.getAllowedActions().contains("CANCEL"));
    }

    @Test
    void buyerAndSellerHaveSeparatedActions() {
        TradeOrder order = new TradeOrder();
        order.setOrderStatus("PAID");
        order.setDisputeStatus("NONE");
        order.setItemSnapshot("{\"deliveryMode\":\"AUTO_CARD\"}");
        LocalDateTime now = LocalDateTime.now();

        List<String> buyerActions = OrderStatusResolver.resolveAllowedActions(order, "BUYER", "AUTO_CARD", now);
        List<String> sellerActions = OrderStatusResolver.resolveAllowedActions(order, "SELLER", "AUTO_CARD", now);

        Assertions.assertTrue(buyerActions.contains("VIEW_CARD"));
        Assertions.assertTrue(buyerActions.contains("OPEN_DISPUTE"));
        Assertions.assertFalse(buyerActions.contains("SELLER_DELIVER"));

        Assertions.assertTrue(sellerActions.contains("OPEN_DISPUTE"));
        Assertions.assertFalse(sellerActions.contains("VIEW_CARD"));
    }
}
