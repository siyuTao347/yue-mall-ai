package com.example.agent.customer.realtime;

import api.agent.AgentActor;
import com.example.agent.customer.conversation.CustomerConversationService;
import com.example.agent.customer.conversation.SessionEventStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.*;

@Component
public class CustomerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerWebSocketHandler.class);

    private final RealtimeSubscriptionService subscriptionService;
    private final CustomerConversationService conversationService;
    private final SessionEventStore eventStore;

    public CustomerWebSocketHandler(RealtimeSubscriptionService subscriptionService,
                                    CustomerConversationService conversationService,
                                    SessionEventStore eventStore) {
        this.subscriptionService = subscriptionService;
        this.conversationService = conversationService;
        this.eventStore = eventStore;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        RealtimePrincipal principal = (RealtimePrincipal) session.getAttributes().get("principal");
        if (principal == null) {
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) {}
            return;
        }
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024);
        session.getAttributes().put("safeSession", safeSession);
        subscriptionService.registerSession(String.valueOf(principal.subjectId()), safeSession);
        log.info("WebSocket connected: principal={}, sessionId={}", principal.getName(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession safeSession = (WebSocketSession) session.getAttributes().getOrDefault("safeSession", session);
        RealtimePrincipal principal = (RealtimePrincipal) session.getAttributes().get("principal");
        if (principal == null) return;

        String payloadText = message.getPayload();
        JsonNode root;
        try {
            root = read(payloadText);
        } catch (Exception e) {
            send(safeSession, RealtimeProtocol.rejected(null, "MALFORMED_FRAME", "非法的 JSON 报文", false));
            return;
        }

        String type = root.path("type").asText("");
        String requestId = root.path("requestId").asText(null);
        String sessionNo = root.path("sessionNo").asText(null);
        JsonNode payload = root.path("payload");

        try {
            switch (type) {
                case RealtimeProtocol.TYPE_SESSION_SUBSCRIBE -> {
                    if (sessionNo == null) throw error(400, "MISSING_SESSION_NO", "缺少 sessionNo");
                    subscriptionService.subscribe(sessionNo, safeSession);

                    // 如果客户端携带了 afterEventSeq，回放补漏事件
                    long afterSeq = payload.path("afterEventSeq").asLong(-1L);
                    if (afterSeq >= 0) {
                        List<Map<String, Object>> events = eventStore.listEventsAfter(sessionNo, afterSeq, 100);
                        for (var ev : events) {
                            long seq = ((Number) ev.get("event_seq")).longValue();
                            String evType = String.valueOf(ev.get("event_type"));
                            JsonNode pl = read(ev.get("public_payload_json"));
                            ObjectNode envelope = RealtimeProtocol.envelope(evType, sessionNo, pl);
                            envelope.put("eventSeq", seq);
                            envelope.put("eventId", String.valueOf(ev.get("event_id")));
                            send(safeSession, envelope);
                        }
                    }
                    long highWatermark = eventStore.getHighWatermark(sessionNo);
                    ObjectNode syncCompleted = RealtimeProtocol.envelope(
                            RealtimeProtocol.TYPE_SESSION_SYNC_COMPLETED,
                            sessionNo,
                            object().put("highWatermark", highWatermark)
                    );
                    send(safeSession, syncCompleted);
                }
                case RealtimeProtocol.TYPE_SESSION_UNSUBSCRIBE -> {
                    if (sessionNo != null) subscriptionService.unsubscribe(sessionNo, safeSession);
                }
                case RealtimeProtocol.TYPE_MESSAGE_SEND -> {
                    if (sessionNo == null) throw error(400, "MISSING_SESSION_NO", "缺少 sessionNo");
                    String clientMessageId = payload.path("clientMessageId").asText("");
                    String content = payload.path("content").asText("");
                    Long expectedEpoch = payload.has("expectedControlEpoch") ? payload.path("expectedControlEpoch").asLong() : null;
                    String senderType = principal.isStaff() ? "STAFF" : "USER";

                    AgentActor actor = new AgentActor(principal.subjectId(), principal.role());
                    CustomerConversationService.SendReceipt receipt = conversationService.sendMessage(
                            actor, sessionNo, clientMessageId, content, expectedEpoch, senderType, payload.path("cards")
                    );

                    // 回复发送端 ACCEPTED ACK
                    ObjectNode ackPayload = object()
                            .put("messageNo", receipt.messageNo())
                            .put("eventSeq", receipt.eventSeq())
                            .put("replayed", receipt.replayed())
                            .put("serviceMode", receipt.serviceMode())
                            .put("controlEpoch", receipt.controlEpoch());
                    ObjectNode accepted = RealtimeProtocol.envelope(RealtimeProtocol.TYPE_MESSAGE_ACCEPTED, sessionNo, ackPayload);
                    if (requestId != null) accepted.put("requestId", requestId);
                    send(safeSession, accepted);
                }
                case RealtimeProtocol.TYPE_READ_MARK -> {
                    if (sessionNo != null && payload.has("lastReadMessageSeq")) {
                        long seq = payload.path("lastReadMessageSeq").asLong();
                        AgentActor actor = new AgentActor(principal.subjectId(), principal.role());
                        conversationService.markRead(actor, sessionNo, seq);
                    }
                }
                case RealtimeProtocol.TYPE_HEARTBEAT -> {
                    ObjectNode ack = RealtimeProtocol.envelope(RealtimeProtocol.TYPE_HEARTBEAT_ACK, sessionNo, object().put("status", "OK"));
                    if (requestId != null) ack.put("requestId", requestId);
                    send(safeSession, ack);
                }
                case RealtimeProtocol.TYPE_TYPING -> {
                    if (sessionNo != null) {
                        ObjectNode typingEnv = RealtimeProtocol.envelope(RealtimeProtocol.TYPE_TYPING, sessionNo, object().put("sender", principal.getName()));
                        subscriptionService.broadcastToSession(sessionNo, typingEnv);
                    }
                }
                default -> {
                    log.debug("Unknown websocket frame type: {}", type);
                }
            }
        } catch (com.example.agent.exception.RagApiException ragEx) {
            send(safeSession, RealtimeProtocol.rejected(requestId, ragEx.errorCode(), ragEx.getMessage(), false));
        } catch (Exception e) {
            log.error("WebSocket message handling error: {}", e.getMessage(), e);
            send(safeSession, RealtimeProtocol.rejected(requestId, "INTERNAL_ERROR", "处理消息时发生异常", true));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RealtimePrincipal principal = (RealtimePrincipal) session.getAttributes().get("principal");
        if (principal != null) {
            WebSocketSession safeSession = (WebSocketSession) session.getAttributes().getOrDefault("safeSession", session);
            subscriptionService.unregisterSession(String.valueOf(principal.subjectId()), safeSession);
            log.info("WebSocket disconnected: principal={}, sessionId={}", principal.getName(), session.getId());
        }
    }

    private static void send(WebSocketSession session, JsonNode json) {
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(write(json)));
            } catch (Exception e) {
                log.warn("Failed to write to WebSocket: {}", e.getMessage());
            }
        }
    }
}
