package api.agent.money;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分批签发资金范围证明响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueFundScopeProofResult implements Serializable {

    private boolean success;
    private String scopeProofRef;
    private String scopeFingerprint;
    private String orderNo;
    private String scopeKey;
    private List<String> verifiedAttempts;
    private List<String> verifiedRefunds;
    private String errorCode;
    private String errorMessage;

    public static IssueFundScopeProofResult fail(String errorCode, String errorMessage) {
        return IssueFundScopeProofResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}

