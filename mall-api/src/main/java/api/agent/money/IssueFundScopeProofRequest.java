package api.agent.money;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分批签发资金范围证明请求 (最多支持 50 个引用)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueFundScopeProofRequest implements Serializable {

    private String orderNo;
    private String grantRef;
    private String scopeKey;
    private List<String> attemptRefs;
    private List<String> refundRefs;
    private List<String> allowedActions;
    private String targetAudience;
}

