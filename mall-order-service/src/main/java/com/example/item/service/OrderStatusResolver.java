package com.example.item.service;

import api.trade.ItemSnapshotDTO;
import com.example.item.dto.UserOrderListDTO;
import com.example.item.entity.TradeOrder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OrderStatusResolver {

    public static UserOrderListDTO toUserOrderDTO(TradeOrder order, String viewRole, Long currentUserId,
                                                  ObjectMapper objectMapper, LocalDateTime now) {
        if (order == null) {
            return null;
        }

        String effectiveView = viewRole;
        if (effectiveView == null || effectiveView.isBlank()) {
            effectiveView = (currentUserId != null && currentUserId.equals(order.getSellerId())) ? "SELLER" : "BUYER";
        }

        ItemSnapshotDTO snapshot = parseSnapshot(order.getItemSnapshot(), objectMapper);
        String itemName = snapshot != null && snapshot.getItemName() != null ? snapshot.getItemName() : "虚拟资产商品";
        String deliveryMode = snapshot != null && snapshot.getDeliveryMode() != null ? snapshot.getDeliveryMode() : "AUTO_CARD";
        String imageUrl = snapshot != null ? snapshot.getImageUrl() : null;

        BigDecimal unitPrice = BigDecimal.ZERO;
        if (snapshot != null && snapshot.getPrice() != null) {
            unitPrice = snapshot.getPrice();
        } else if (order.getOrderAmount() != null && order.getQuantity() != null && order.getQuantity() > 0) {
            unitPrice = order.getOrderAmount().divide(BigDecimal.valueOf(order.getQuantity()), 2, RoundingMode.HALF_UP);
        }

        BigDecimal orderAmt = order.getOrderAmount() != null ? order.getOrderAmount() : BigDecimal.ZERO;
        boolean isPaid = "PAID".equals(order.getOrderStatus()) || "DELIVERED".equals(order.getOrderStatus())
                || "CONFIRMED".equals(order.getOrderStatus()) || "SETTLING".equals(order.getOrderStatus())
                || "SETTLED".equals(order.getOrderStatus()) || "PAID".equals(order.getPayStatus());
        BigDecimal paidAmt = isPaid ? orderAmt : BigDecimal.ZERO;
        BigDecimal refundedAmt = "REFUNDED".equals(order.getOrderStatus()) ? orderAmt : BigDecimal.ZERO;

        String source = "NORMAL";
        if (order.getOrderNo() != null) {
            if (order.getOrderNo().startsWith("SEC") || order.getOrderNo().startsWith("ORD")) {
                source = "SECKILL";
            } else if (order.getOrderNo().startsWith("TCC")) {
                source = "LEGACY";
            }
        }

        String statusGroup = resolveStatusGroup(order);
        String displayStatus = resolveDisplayStatus(order);
        String statusDesc = resolveStatusDescription(order, effectiveView);
        List<String> allowedActions = resolveAllowedActions(order, effectiveView, deliveryMode, now);

        String counterpartyName = "BUYER".equals(effectiveView)
                ? ("卖家 #" + order.getSellerId())
                : ("买家 #" + order.getBuyerId());
        Long counterpartyId = "BUYER".equals(effectiveView) ? order.getSellerId() : order.getBuyerId();

        return UserOrderListDTO.builder()
                .orderNo(order.getOrderNo())
                .orderVersion(order.getVersion())
                .viewRole(effectiveView)
                .source(source)
                .id(order.getId())
                .buyerId(order.getBuyerId())
                .sellerId(order.getSellerId())
                .merchantId(order.getMerchantId())
                .itemId(order.getItemId())
                .quantity(order.getQuantity())
                .unitPrice(unitPrice.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .orderAmount(orderAmt.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .paidAmount(paidAmt.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .refundedAmount(refundedAmt.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .feeAmount(order.getFeeAmount() != null ? order.getFeeAmount().setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00")
                .sellerIncome(order.getSellerIncome() != null ? order.getSellerIncome().setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00")
                .currency("CNY")
                .priceConfirmed(!"CREATE_PENDING".equals(order.getOrderStatus()))
                .orderStatus(order.getOrderStatus())
                .payStatus(order.getPayStatus())
                .deliveryStatus(order.getDeliveryStatus())
                .escrowStatus(order.getEscrowStatus())
                .disputeStatus(order.getDisputeStatus())
                .statusGroup(statusGroup)
                .displayStatus(displayStatus)
                .statusDescription(statusDesc)
                .paymentNo(order.getPaymentNo())
                .allowedActions(allowedActions)
                .payDeadline(order.getPayDeadline())
                .deliveryDeadline(order.getDeliveryDeadline())
                .deliveredTime(order.getDeliveredTime())
                .confirmedTime(order.getConfirmedTime())
                .autoConfirmTime(order.getAutoConfirmTime())
                .settleAvailableTime(order.getSettleAvailableTime())
                .settledTime(order.getSettledTime())
                .itemSnapshot(order.getItemSnapshot())
                .item(UserOrderListDTO.ItemDetail.builder()
                        .itemId(order.getItemId() != null ? String.valueOf(order.getItemId()) : null)
                        .name(itemName)
                        .imageUrl(imageUrl)
                        .deliveryMode(deliveryMode)
                        .build())
                .counterparty(UserOrderListDTO.Counterparty.builder()
                        .displayName(counterpartyName)
                        .id(counterpartyId)
                        .build())
                .createdTime(order.getCreatedTime())
                .updatedTime(order.getUpdatedTime())
                .build();
    }

    public static String resolveStatusGroup(TradeOrder order) {
        String disputeStatus = order.getDisputeStatus();
        String orderStatus = order.getOrderStatus();

        if (disputeStatus != null && !"NONE".equals(disputeStatus)) {
            return "AFTER_SALE";
        }
        if ("REFUNDING".equals(orderStatus) || "REFUNDED".equals(orderStatus)) {
            return "AFTER_SALE";
        }
        if ("CANCELLED".equals(orderStatus) || "CLOSED".equals(orderStatus) || "REJECTED".equals(orderStatus)) {
            return "CLOSED";
        }
        if ("CREATE_PENDING".equals(orderStatus) || "PAY_CONFIRMING".equals(orderStatus) || "CANCELLING".equals(orderStatus)) {
            return "PROCESSING";
        }
        if ("WAIT_PAY".equals(orderStatus)) {
            return "WAIT_PAY";
        }
        if ("PAID".equals(orderStatus)) {
            return "WAIT_DELIVERY";
        }
        if ("DELIVERED".equals(orderStatus)) {
            return "WAIT_CONFIRM";
        }
        if ("CONFIRMED".equals(orderStatus) || "SETTLING".equals(orderStatus) || "SETTLED".equals(orderStatus)) {
            return "COMPLETED";
        }
        return "ALL";
    }

    public static String resolveDisplayStatus(TradeOrder order) {
        String disputeStatus = order.getDisputeStatus();
        if (disputeStatus != null && !"NONE".equals(disputeStatus)) {
            return "ARBITRATING".equals(disputeStatus) ? "AFTER_SALE_PROCESSING" : ("AFTER_SALE_" + disputeStatus);
        }
        return order.getOrderStatus();
    }

    public static String resolveStatusDescription(TradeOrder order, String viewRole) {
        String status = order.getOrderStatus();
        String disputeStatus = order.getDisputeStatus();

        if (disputeStatus != null && !"NONE".equals(disputeStatus)) {
            return "售后处理中 (" + disputeStatus + ")";
        }
        if ("CREATE_PENDING".equals(status)) {
            return "订单创建中，正在确认商品与库存";
        }
        if ("WAIT_PAY".equals(status)) {
            return "待支付，请在截止时间前完成支付";
        }
        if ("PAY_CONFIRMING".equals(status)) {
            return "支付结果确认中，请稍候";
        }
        if ("CANCELLING".equals(status)) {
            return "正在取消，等待库存释放";
        }
        if ("PAID".equals(status)) {
            return "BUYER".equals(viewRole) ? "已支付，等待商家交付" : "买家已支付，请及时交付";
        }
        if ("DELIVERED".equals(status)) {
            return "BUYER".equals(viewRole) ? "商品已交付，请确认收货" : "已完成交付，等待买家确认";
        }
        if ("CONFIRMED".equals(status) || "SETTLING".equals(status)) {
            return "BUYER".equals(viewRole) ? "交易已完成" : "交易已完成，结算排队中";
        }
        if ("SETTLED".equals(status)) {
            return "交易已结算完成";
        }
        if ("REFUNDING".equals(status)) {
            return "退款处理中";
        }
        if ("REFUNDED".equals(status)) {
            return "已退款至平台账户余额";
        }
        if ("CANCELLED".equals(status)) {
            return "订单已取消";
        }
        return "订单状态: " + status;
    }

    public static List<String> resolveAllowedActions(TradeOrder order, String viewRole, String deliveryMode, LocalDateTime now) {
        List<String> actions = new ArrayList<>();
        actions.add("VIEW_DETAIL");

        boolean isBuyer = "BUYER".equals(viewRole);
        boolean isSeller = "SELLER".equals(viewRole);
        String orderStatus = order.getOrderStatus();
        String disputeStatus = order.getDisputeStatus();
        boolean hasDispute = disputeStatus != null && !"NONE".equals(disputeStatus);

        if (isBuyer) {
            if ("WAIT_PAY".equals(orderStatus)) {
                if (order.getPayDeadline() == null || order.getPayDeadline().isAfter(now)) {
                    actions.add("PAY");
                }
                actions.add("CANCEL");
            }
            if ("DELIVERED".equals(orderStatus) && !hasDispute) {
                actions.add("CONFIRM_RECEIPT");
            }
            if (("PAID".equals(orderStatus) || "DELIVERED".equals(orderStatus)) && !hasDispute) {
                actions.add("OPEN_DISPUTE");
            }
            if ("AUTO_CARD".equals(deliveryMode)
                    && ("PAID".equals(orderStatus) || "DELIVERED".equals(orderStatus) || "CONFIRMED".equals(orderStatus) || "SETTLING".equals(orderStatus) || "SETTLED".equals(orderStatus))) {
                actions.add("VIEW_CARD");
            }
            if ("CONFIRMED".equals(orderStatus) || "SETTLING".equals(orderStatus) || "SETTLED".equals(orderStatus)) {
                actions.add("REVIEW");
            }
        } else if (isSeller) {
            if ("PAID".equals(orderStatus) && !hasDispute) {
                actions.add("SELLER_DELIVER");
            }
            if (("PAID".equals(orderStatus) || "DELIVERED".equals(orderStatus)) && !hasDispute) {
                actions.add("OPEN_DISPUTE");
            }
        }

        return actions;
    }

    private static ItemSnapshotDTO parseSnapshot(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ItemSnapshotDTO.class);
        } catch (Exception e) {
            return null;
        }
    }
}
