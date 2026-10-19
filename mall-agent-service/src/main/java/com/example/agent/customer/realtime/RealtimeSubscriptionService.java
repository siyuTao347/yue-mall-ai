package com.example.agent.customer.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.agent.customer.CustomerJson.write;

@Service
public class RealtimeSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSubscriptionService.class);

    // sessionNo -> Set<WebSocketSession>
    private final Map<String, Set<WebSocketSession>> sessionSubscribers = new ConcurrentHashMap<>();
    // userId / staffId -> Set<WebSocketSession> (用于个人通知，如 inbox.changed)
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    public void registerSession(String userOrStaffKey, WebSocketSession session) {
        userSessions.computeIfAbsent(userOrStaffKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(session);
    }

    public void unregisterSession(String userOrStaffKey, WebSocketSession session) {
        Set<WebSocketSession> set = userSessions.get(userOrStaffKey);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) userSessions.remove(userOrStaffKey);
        }
        // Remove from all subscribed sessions
        sessionSubscribers.values().forEach(subscribers -> subscribers.remove(session));
    }

    public void subscribe(String sessionNo, WebSocketSession session) {
        sessionSubscribers.computeIfAbsent(sessionNo, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(session);
    }

    public void unsubscribe(String sessionNo, WebSocketSession session) {
        Set<WebSocketSession> set = sessionSubscribers.get(sessionNo);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) sessionSubscribers.remove(sessionNo);
        }
    }

    public void broadcastToSession(String sessionNo, JsonNode envelope) {
        Set<WebSocketSession> sessions = sessionSubscribers.get(sessionNo);
        if (sessions == null || sessions.isEmpty()) return;
        String text = write(envelope);
        TextMessage message = new TextMessage(text);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (Exception e) {
                    log.warn("Failed to send message to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    public void sendToUser(String userOrStaffKey, JsonNode envelope) {
        Set<WebSocketSession> sessions = userSessions.get(userOrStaffKey);
        if (sessions == null || sessions.isEmpty()) return;
        String text = write(envelope);
        TextMessage message = new TextMessage(text);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (Exception e) {
                    log.warn("Failed to send direct message to {}: {}", userOrStaffKey, e.getMessage());
                }
            }
        }
    }
}
