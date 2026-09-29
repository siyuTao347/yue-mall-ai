package com.example.risk.service;

import java.util.Set;

public final class RiskMetricCodes {
    public static final Set<String> ALLOWED = Set.of(
            "CURRENT_AMOUNT",
            "USER_AGE_HOURS",
            "USER_ORDER_COUNT_10M",
            "USER_ORDER_COUNT_24H",
            "USER_COMPLETED_COUNT",
            "USER_DISPUTE_COUNT_7D",
            "USER_DEVICE_COUNT_30D",
            "USER_IP_COUNT_30D",
            "MERCHANT_AGE_HOURS",
            "MERCHANT_COMPLETED_COUNT",
            "MERCHANT_REFUND_RATE_30D",
            "MERCHANT_DISPUTE_RATE_30D",
            "MERCHANT_WITHDRAW_AMOUNT_24H",
            "WITHDRAW_AMOUNT",
            "WITHDRAW_ACCOUNT_MERCHANT_COUNT",
            "ITEM_PRICE_DEVIATION",
            "BUYER_SELLER_RELATION",
            "SENSITIVE_WORD_CATEGORY",
            "ORDER_RISK_STATUS",
            "ORDER_PAY_TO_DELIVER_SECONDS",
            "ORDER_VIEW_TO_CONFIRM_SECONDS",
            "MERCHANT_SETTLE_TO_WITHDRAW_MINUTES"
            , "REGISTER_SAME_IP_COUNT_10M"
            , "REGISTER_SAME_DEVICE_COUNT_30D"
            , "LOGIN_FAIL_COUNT_10M"
    );

    private RiskMetricCodes() {
    }
}
