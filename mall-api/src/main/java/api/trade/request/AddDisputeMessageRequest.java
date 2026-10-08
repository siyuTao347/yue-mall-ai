package api.trade.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 售后留言请求。
 */
public record AddDisputeMessageRequest(
        @NotBlank(message = "留言内容不能为空")
        @Size(max = 500, message = "留言最长 500 个字符")
        String message
) {
}
