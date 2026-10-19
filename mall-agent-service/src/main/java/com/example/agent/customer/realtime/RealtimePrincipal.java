package com.example.agent.customer.realtime;

import java.io.Serializable;
import java.security.Principal;

public record RealtimePrincipal(
        long subjectId,
        String realm,     // "USER" or "STAFF"
        String role,      // "USER", "ADMIN", "AGENT", "SUPERVISOR"
        String staffId
) implements Principal, Serializable {

    @Override
    public String getName() {
        return realm + ":" + (staffId != null ? staffId : subjectId);
    }

    public boolean isStaff() {
        return "STAFF".equalsIgnoreCase(realm);
    }
}
