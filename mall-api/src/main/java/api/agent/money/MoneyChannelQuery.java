package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 渠道只读观察查询条件
 */
public class MoneyChannelQuery implements Serializable {
    private static final long serialVersionUID = 1L;

    private String scopeKey;
    private String attemptNo;
    private String refundNo;
    private String profileSnapshot;

    public MoneyChannelQuery() {}

    public MoneyChannelQuery(String scopeKey, String attemptNo, String refundNo, String profileSnapshot) {
        this.scopeKey = scopeKey;
        this.attemptNo = attemptNo;
        this.refundNo = refundNo;
        this.profileSnapshot = profileSnapshot;
    }

    public static MoneyChannelQuery forAttempt(String scopeKey, String attemptNo, String profileSnapshot) {
        return new MoneyChannelQuery(scopeKey, attemptNo, null, profileSnapshot);
    }

    public static MoneyChannelQuery forRefund(String scopeKey, String refundNo, String profileSnapshot) {
        return new MoneyChannelQuery(scopeKey, null, refundNo, profileSnapshot);
    }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public String getAttemptNo() { return attemptNo; }
    public void setAttemptNo(String attemptNo) { this.attemptNo = attemptNo; }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }

    public String getProfileSnapshot() { return profileSnapshot; }
    public void setProfileSnapshot(String profileSnapshot) { this.profileSnapshot = profileSnapshot; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneyChannelQuery that)) return false;
        return Objects.equals(scopeKey, that.scopeKey) && Objects.equals(attemptNo, that.attemptNo) &&
                Objects.equals(refundNo, that.refundNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeKey, attemptNo, refundNo);
    }
}

