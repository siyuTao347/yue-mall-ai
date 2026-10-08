package api.trade.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员仲裁请求：阶段一仅支持 REFUND_ALL / RELEASE_ALL。
 */
public record ArbitrateDisputeRequest(
        @NotBlank(message = "仲裁结果不能为空")
        @Size(max = 32, message = "仲裁结果最长 32 个字符")
        String result,

        @Size(max = 500, message = "仲裁说明最长 500 个字符")
        String reason
) {
}
