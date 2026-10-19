package com.example.agent.customer.handoff;

import com.example.agent.customer.CustomerDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class StaffPresenceService {

    private static final String PRESENCE_KEY_PREFIX = "staff:presence:";
    private final CustomerDatabase db;
    private final StringRedisTemplate redis;

    @Autowired
    public StaffPresenceService(CustomerDatabase db, @Autowired(required = false) StringRedisTemplate redis) {
        this.db = db;
        this.redis = redis;
    }

    public void updateAvailability(long userId, String availability) {
        db.mysql.update(
                "UPDATE t_agent_staff SET availability = ?, updated_time = now(3) WHERE user_id = ?",
                availability, userId
        );
        if (redis != null) {
            redis.opsForValue().set(PRESENCE_KEY_PREFIX + userId, availability, Duration.ofMinutes(30));
        }
    }

    public void recordHeartbeat(long userId) {
        if (redis != null) {
            redis.opsForValue().set(PRESENCE_KEY_PREFIX + userId, "ONLINE", Duration.ofSeconds(75));
        }
    }

    public boolean isStaffAvailable(long userId) {
        if (redis != null) {
            String val = redis.opsForValue().get(PRESENCE_KEY_PREFIX + userId);
            if ("AVAILABLE".equalsIgnoreCase(val) || "ONLINE".equalsIgnoreCase(val)) {
                return true;
            }
        }
        var rows = db.mysql.queryForList(
                "SELECT availability FROM t_agent_staff WHERE user_id = ? AND enabled = 1",
                userId
        );
        return !rows.isEmpty() && "AVAILABLE".equalsIgnoreCase(String.valueOf(rows.getFirst().get("availability")));
    }
}
