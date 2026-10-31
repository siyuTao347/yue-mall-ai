package api.agent.money;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 来源覆盖范围与分页信息
 */
public class CoverageInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Map<String, String> sources = new HashMap<>();
    private boolean complete;
    private boolean hasMore;
    private String cursor;
    private String notes;

    public CoverageInfo() {}

    public CoverageInfo(Map<String, String> sources, boolean complete, boolean hasMore, String cursor, String notes) {
        if (sources != null) {
            this.sources = new HashMap<>(sources);
        }
        this.complete = complete;
        this.hasMore = hasMore;
        this.cursor = cursor;
        this.notes = notes;
    }

    public static CoverageInfo ok(String sourceName) {
        CoverageInfo info = new CoverageInfo();
        info.sources.put(sourceName, MoneyCoverageStatus.OK.name());
        info.complete = true;
        info.hasMore = false;
        return info;
    }

    public Map<String, String> getSources() { return sources; }
    public void setSources(Map<String, String> sources) { this.sources = sources != null ? sources : new HashMap<>(); }

    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }

    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }

    public String getCursor() { return cursor; }
    public void setCursor(String cursor) { this.cursor = cursor; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CoverageInfo that)) return false;
        return complete == that.complete && hasMore == that.hasMore &&
                Objects.equals(sources, that.sources) && Objects.equals(cursor, that.cursor) &&
                Objects.equals(notes, that.notes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sources, complete, hasMore, cursor, notes);
    }
}

