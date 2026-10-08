package api.trade.request;

import jakarta.validation.constraints.Size;

/**
 * 提交交付证据请求：类型缺省为 TEXT。
 */
public record AddEvidenceRequest(
        @Size(max = 32, message = "证据类型最长 32 个字符")
        String evidenceType,

        @Size(max = 512, message = "文件地址最长 512 个字符")
        String fileUrl,

        @Size(max = 2000, message = "证据说明最长 2000 个字符")
        String content
) {
}
