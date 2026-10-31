package api.agent.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 事实证据质量评级
 */
public class FactQuality implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean identityMatched;
    private String coverage; // COMPLETE, PARTIAL, TRUNCATED
    private String linkQuality; // EXACT, MAPPED, LEGACY_UNLINKED, CONFLICT
    private boolean conflict;

    public FactQuality() {}

    public FactQuality(boolean identityMatched, String coverage, String linkQuality, boolean conflict) {
        this.identityMatched = identityMatched;
        this.coverage = coverage;
        this.linkQuality = linkQuality;
        this.conflict = conflict;
    }

    public static FactQuality exact() {
        return new FactQuality(true, "COMPLETE", MoneyLinkQuality.EXACT.name(), false);
    }

    public boolean isIdentityMatched() { return identityMatched; }
    public void setIdentityMatched(boolean identityMatched) { this.identityMatched = identityMatched; }

    public String getCoverage() { return coverage; }
    public void setCoverage(String coverage) { this.coverage = coverage; }

    public String getLinkQuality() { return linkQuality; }
    public void setLinkQuality(String linkQuality) { this.linkQuality = linkQuality; }

    public boolean isConflict() { return conflict; }
    public void setConflict(boolean conflict) { this.conflict = conflict; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FactQuality that)) return false;
        return identityMatched == that.identityMatched && conflict == that.conflict &&
                Objects.equals(coverage, that.coverage) && Objects.equals(linkQuality, that.linkQuality);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identityMatched, coverage, linkQuality, conflict);
    }
}

