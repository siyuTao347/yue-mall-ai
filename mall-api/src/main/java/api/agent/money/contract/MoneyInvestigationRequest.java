package api.agent.money.contract;

import api.agent.money.MoneyProblemFamily;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 资金调查业务请求 (mall.money.investigation-request.v1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyInvestigationRequest implements Serializable {

    public static final String SCHEMA_V1 = "mall.money.investigation-request.v1";

    /** schema 标识，必须为 mall.money.investigation-request.v1 */
    @Builder.Default
    private String schema = SCHEMA_V1;

    /** 原调用方稳定去重键 (1-128字符) */
    private String requestId;

    /** 来源类型: CUSTOMER / FINANCE / DOMAIN_EVENT */
    private String origin;

    /** 调查主体 */
    private Subject subject;

    /** 问题族枚举 */
    private MoneyProblemFamily problemFamily;

    /** 脱敏陈述 */
    private Claim claim;

    /** 关联案号 (可选) */
    private String relatedCaseNo;

    /** 委托引用 */
    private String delegationRef;

    /** 调用方上下文 (可选) */
    private CallerContext callerContext;

    /** 预期作用域指纹 */
    private String expectedScopeFingerprint;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Subject implements Serializable {
        private String scopeKey;
        private String orderNo;
        private String attemptNo;
        private String refundNo;
        private String diffNo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Claim implements Serializable {
        private String text;
        private String sourceMessageRef;
        private String reportedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallerContext implements Serializable {
        private String traceId;
        private String dispatchNo;
        private String customerRunNo;
        private String intentInstanceId;
    }
}

