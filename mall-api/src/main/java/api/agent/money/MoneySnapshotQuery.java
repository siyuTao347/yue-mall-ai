package api.agent.money;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 订单资金快照查询条件
 */
public class MoneySnapshotQuery implements Serializable {
    private static final long serialVersionUID = 1L;

    private String scopeKey;
    private List<String> subjectRefs = new ArrayList<>();
    private String cursor;
    private Integer pageSize;
    private String asOfRequestTime;

    public MoneySnapshotQuery() {}

    public MoneySnapshotQuery(String scopeKey, List<String> subjectRefs, String cursor, Integer pageSize, String asOfRequestTime) {
        this.scopeKey = scopeKey;
        if (subjectRefs != null) {
            this.subjectRefs = new ArrayList<>(subjectRefs);
        }
        this.cursor = cursor;
        this.pageSize = pageSize != null ? pageSize : 50;
        this.asOfRequestTime = asOfRequestTime;
    }

    public static MoneySnapshotQuery forScope(String scopeKey) {
        MoneySnapshotQuery q = new MoneySnapshotQuery();
        q.setScopeKey(scopeKey);
        q.setPageSize(50);
        return q;
    }

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }

    public List<String> getSubjectRefs() { return subjectRefs; }
    public void setSubjectRefs(List<String> subjectRefs) {
        this.subjectRefs = subjectRefs != null ? new ArrayList<>(subjectRefs) : new ArrayList<>();
    }

    public String getCursor() { return cursor; }
    public void setCursor(String cursor) { this.cursor = cursor; }

    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }

    public String getAsOfRequestTime() { return asOfRequestTime; }
    public void setAsOfRequestTime(String asOfRequestTime) { this.asOfRequestTime = asOfRequestTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoneySnapshotQuery that)) return false;
        return Objects.equals(scopeKey, that.scopeKey) &&
                Objects.equals(subjectRefs, that.subjectRefs) &&
                Objects.equals(cursor, that.cursor) &&
                Objects.equals(pageSize, that.pageSize) &&
                Objects.equals(asOfRequestTime, that.asOfRequestTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeKey, subjectRefs, cursor, pageSize, asOfRequestTime);
    }
}

