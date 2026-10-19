package com.example.agent.customer.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import static com.example.agent.customer.CustomerJson.object;

public final class RealtimeProtocol {
    private RealtimeProtocol() {}

    public static final int PROTOCOL_VERSION = 1;
    public static final int MESSAGE_SCHEMA_VERSION = 3;

    // 命令与事件类型常量
    public static final String TYPE_MESSAGE_SEND = "message.send";
    public static final String TYPE_MESSAGE_ACCEPTED = "message.accepted";
    public static final String TYPE_MESSAGE_CREATED = "message.created";
    public static final String TYPE_SESSION_SUBSCRIBE = "session.subscribe";
    public static final String TYPE_SESSION_UNSUBSCRIBE = "session.unsubscribe";
    public static final String TYPE_SESSION_MODE_CHANGED = "session.mode.changed";
    public static final String TYPE_HANDOFF_CHANGED = "handoff.changed";
    public static final String TYPE_CARD_UPDATED = "card.updated";
    public static final String TYPE_WORKFLOW_PUBLIC_CHANGED = "workflow.public.changed";
    public static final String TYPE_SESSION_SYNC_COMPLETED = "session.sync.completed";
    public static final String TYPE_DELIVERY_ACK = "delivery.ack";
    public static final String TYPE_READ_MARK = "read.mark";
    public static final String TYPE_ANSWER_DELTA = "answer.delta";
    public static final String TYPE_TYPING = "typing";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_HEARTBEAT_ACK = "heartbeat.ack";
    public static final String TYPE_INBOX_CHANGED = "inbox.changed";
    public static final String TYPE_QUEUE_CHANGED = "queue.changed";
    public static final String TYPE_COPILOT_READY = "copilot.ready";
    public static final String TYPE_COMMAND_REJECTED = "command.rejected";

    public static ObjectNode envelope(String type, String sessionNo, JsonNode payload) {
        ObjectNode node = object();
        node.put("protocolVersion", PROTOCOL_VERSION);
        node.put("type", type);
        if (sessionNo != null) node.put("sessionNo", sessionNo);
        node.put("serverTime", Instant.now().toString());
        if (payload != null) node.set("payload", payload);
        return node;
    }

    public static ObjectNode rejected(String requestId, String code, String message, boolean retryable) {
        ObjectNode node = object();
        node.put("protocolVersion", PROTOCOL_VERSION);
        node.put("type", TYPE_COMMAND_REJECTED);
        if (requestId != null) node.put("requestId", requestId);
        node.put("serverTime", Instant.now().toString());
        ObjectNode payload = object();
        payload.put("errorCode", code);
        payload.put("errorMessage", message);
        payload.put("retryable", retryable);
        node.set("payload", payload);
        return node;
    }
}
