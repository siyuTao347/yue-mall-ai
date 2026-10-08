package api.trade.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建担保交易订单请求。
 */
public record CreateOrderRequest(
        @NotNull(message = "商品不能为空")
        @Positive(message = "商品不合法")
        Long itemId,

        @NotNull(message = "购买数量不能为空")
        @Min(value = 1, message = "购买数量至少为 1")
        @Max(value = 10, message = "购买数量最多为 10")
        Integer quantity
) {
}
