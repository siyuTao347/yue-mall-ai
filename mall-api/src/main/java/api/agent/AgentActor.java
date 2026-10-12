package api.agent;

import java.io.Serializable;

/** Service-created identity. Customer tools never grant an administrator ownership bypass. */
public record AgentActor(long userId, String role) implements Serializable {
    public AgentActor {
        if (userId <= 0) throw new IllegalArgumentException("Invalid actor");
        role = role == null ? "USER" : role;
    }
}
