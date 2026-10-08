package api.trade.request;

import jakarta.validation.constraints.Size;

/**
 * 卖家交付请求：自动发货商品允许内容为空。
 */
public record DeliverOrderRequest(
        @Size(max = 2000, message = "交付说明最长 2000 个字符")
        String content
) {
}
