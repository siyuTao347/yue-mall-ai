package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 跨服务受限调用凭据与上下文
 */
public class MoneyAccessContext implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 服务间身份凭据 */
    private String serviceIdentityProof;
    /** 用户/运营委托票据 */
    private String delegationProof;
    /** Order 签发的可验证范围凭据 (包含授权对象、版本、有效期与 HMAC 签名) */
    private String scopeProofRef;
    /** 调用动作名称 */
    private String action;
    /** 调用方请求 ID (用于追踪与幂等) */
    private String requestId;
    /** 分布式追踪 ID */
    private String traceId;
    /** 来源调用方服务名 */
    private String callerService;
    /** 操作主体用户/员工 ID */
    private Long actorUserId;
    /** 角色：BUYER / CUSTOMER_STAFF / FINANCE / SYSTEM */
    private String actorRole;

    public MoneyAccessContext() {}

    public MoneyAccessContext(String serviceIdentityProof, String delegationProof, String scopeProofRef,
                              String action, String requestId, String traceId, String callerService,
                              Long actorUserId, String actorRole) {
        this.serviceIdentityProof = serviceIdentityProof;
        this.delegationProof = delegationProof;
        this.scopeProofRef = scopeProofRef;
        this.action = action;
        this.requestId = requestId;
        this.traceId = traceId;
        this.callerService = callerService;
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
    }

    public String getServiceIdentityProof() { return serviceIdentityProof; }
    public void setServiceIdentityProof(String serviceIdentityProof) { this.serviceIdentityProof = serviceIdentityProof; }

    public String getDelegationProof() { return delegationProof; }
    public void setDelegationProof(String delegationProof) { this.delegationProof = delegationProof; }

    public String getScopeProofRef() { return scopeProofRef; }
    public void setScopeProofRef(String scopeProofRef) { this.scopeProofRef = scopeProofRef; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getCallerService() { return callerService; }
    public void setCallerService(String callerService) { this.callerService = callerService; }

    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyAccessContext that)) return false;
        return Objects.equals(serviceIdentityProof, that.serviceIdentityProof) &&
                Objects.equals(delegationProof, that.delegationProof) &&
                Objects.equals(scopeProofRef, that.scopeProofRef) &&
                Objects.equals(action, that.action) &&
                Objects.equals(requestId, that.requestId) &&
                Objects.equals(callerService, that.callerService) &&
                Objects.equals(actorUserId, that.actorUserId) &&
                Objects.equals(actorRole, that.actorRole);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceIdentityProof, delegationProof, scopeProofRef, action, requestId, callerService, actorUserId, actorRole);
    }
}

