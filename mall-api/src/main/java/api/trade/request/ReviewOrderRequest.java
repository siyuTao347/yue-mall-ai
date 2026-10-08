package api.trade.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 订单评价请求：评分 1~5，内容可选。
 */
public record ReviewOrderRequest(
        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分最低 1 分")
        @Max(value = 5, message = "评分最高 5 分")
        Integer score,

        @Size(max = 500, message = "评价内容最长 500 个字符")
        String content
) {
}
