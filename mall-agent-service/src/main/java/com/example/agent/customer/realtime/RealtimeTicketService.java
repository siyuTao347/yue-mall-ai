package com.example.agent.customer.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

import static com.example.agent.customer.CustomerJson.*;

@Service
public class RealtimeTicketService {

    private static final String TICKET_PREFIX = "rt:ticket:";
    private final StringRedisTemplate redis;

    @Autowired
    public RealtimeTicketService(@Autowired(required = false) StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String createTicket(long subjectId, String realm, String role, String staffId) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        var node = object()
                .put("subjectId", subjectId)
                .put("realm", realm)
                .put("role", role)
                .put("staffId", staffId)
                .put("createdAt", System.currentTimeMillis());

        if (redis != null) {
            redis.opsForValue().set(TICKET_PREFIX + jti, write(node), Duration.ofSeconds(60));
        }
        return jti;
    }

    public RealtimePrincipal consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        if (redis == null) {
            // Local dev fallback if Redis is unavailable: return dummy guest user
            return new RealtimePrincipal(1L, "USER", "USER", null);
        }
        String key = TICKET_PREFIX + ticket;
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) return null;
        try {
            JsonNode n = read(raw);
            long subjectId = n.path("subjectId").asLong();
            String realm = n.path("realm").asText("USER");
            String role = n.path("role").asText("USER");
            String staffId = n.has("staffId") && !n.path("staffId").isNull() ? n.path("staffId").asText() : null;
            return new RealtimePrincipal(subjectId, realm, role, staffId);
        } catch (Exception e) {
            return null;
        }
    }
}
