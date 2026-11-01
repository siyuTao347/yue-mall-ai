package com.example.user.service;

import java.io.Serializable;

/**
 * 强类型资金业务关联参数 (贯穿入账、退款、重试与幂等校验)
 */
public record FundBusinessReferences(
        String orderNo,
        String attemptNo,
        String refundNo,
        String operationId
) implements Serializable {

    public static FundBusinessReferences of(String orderNo, String attemptNo, String refundNo, String operationId) {
        return new FundBusinessReferences(orderNo, attemptNo, refundNo, operationId);
    }

    public static FundBusinessReferences forOrder(String orderNo) {
        return new FundBusinessReferences(orderNo, null, null, null);
    }

    public static FundBusinessReferences forAttempt(String orderNo, String attemptNo) {
        return new FundBusinessReferences(orderNo, attemptNo, null, null);
    }

    public static FundBusinessReferences forRefund(String orderNo, String refundNo) {
        return new FundBusinessReferences(orderNo, null, refundNo, null);
    }

    public static FundBusinessReferences forOperation(String operationId) {
        return new FundBusinessReferences(null, null, null, operationId);
    }
}

