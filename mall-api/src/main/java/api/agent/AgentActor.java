package api.agent;

import java.io.Serializable;
import java.util.Objects;

/** Service-created identity. Customer tools never grant an administrator ownership bypass. */
public class AgentActor implements Serializable {
    private static final long serialVersionUID = 1L;

    private long userId;
    private String role;

    public AgentActor() {}

    public AgentActor(long userId, String role) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid actor");
        this.userId = userId;
        this.role = role == null ? "USER" : role;
    }

    public long userId() { return userId; }
    public String role() { return role; }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentActor that)) return false;
        return userId == that.userId && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, role);
    }

    @Override
    public String toString() {
        return "AgentActor[userId=" + userId + ", role=" + role + "]";
    }
}
