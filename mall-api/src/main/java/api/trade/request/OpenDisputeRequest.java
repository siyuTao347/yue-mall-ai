package api.trade.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 发起售后请求：买家/卖家在 PAID 或 DELIVERED 状态下可发起。
 */
public record OpenDisputeRequest(
        @NotBlank(message = "订单号不能为空")
        @Size(max = 64, message = "订单号最长 64 个字符")
        String orderNo,

        @NotBlank(message = "售后类型不能为空")
        @Size(max = 32, message = "售后类型最长 32 个字符")
        String disputeType,

        @NotBlank(message = "售后原因不能为空")
        @Size(max = 500, message = "售后原因最长 500 个字符")
        String reason,

        @DecimalMin(value = "0.01", message = "退款金额必须大于 0")
        BigDecimal refundAmount
) {
}
