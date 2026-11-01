package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 传输层双层鉴权凭据
 * <p>
 * 包含调用方服务身份证明与最终用户委托证明
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCallerCredential implements Serializable {

    /** 服务身份票据 (Authorization: Bearer) */
    private String serviceIdentityToken;

    /** 委托授权短期票据 (X-Money-Delegation) */
    private String delegationToken;

    /** 调用方服务名 */
    private String callerService;

    /** 发起用户/主体 ID */
    private Long actorUserId;

    /** 发起主体角色 (BUYER / FINANCE / SYSTEM) */
    private String actorRole;

    /** 关联持久授权引用 */
    private String grantRef;

    /** 作用域标识 (例如 ORDER:T1001) */
    private String scopeKey;
}

