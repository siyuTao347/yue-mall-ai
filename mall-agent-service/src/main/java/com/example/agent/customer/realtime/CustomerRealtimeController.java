package com.example.agent.customer.realtime;

import api.agent.AgentActor;
import api.response.ApiResponse;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.ConversationMessageStore;
import com.example.agent.customer.conversation.CustomerConversationService;
import com.example.agent.customer.conversation.SessionEventStore;
import com.example.agent.customer.handoff.CustomerHandoffService;
import com.example.agent.customer.richui.CardActionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.object;

@RestController
@RequestMapping("/api/agent")
public class CustomerRealtimeController {

    private final RealtimeTicketService ticketService;
    private final ConversationMessageStore messageStore;
    private final SessionEventStore eventStore;
    private final CustomerConversationService conversationService;
    private final CustomerHandoffService handoffService;
    private final CardActionService cardActionService;
    private final CustomerDatabase db;

    public CustomerRealtimeController(RealtimeTicketService ticketService,
                                      ConversationMessageStore messageStore,
                                      SessionEventStore eventStore,
                                      CustomerConversationService conversationService,
                                      CustomerHandoffService handoffService,
                                      CardActionService cardActionService,
                                      CustomerDatabase db) {
        this.ticketService = ticketService;
        this.messageStore = messageStore;
        this.eventStore = eventStore;
        this.conversationService = conversationService;
        this.handoffService = handoffService;
        this.cardActionService = cardActionService;
        this.db = db;
    }

    @PostMapping("/realtime/tickets")
    public Object createTicket(AgentActor actor) {
        String ticket = ticketService.createTicket(actor.userId(), "USER", actor.role(), null);
        return ApiResponse.success(Map.of(
                "ticket", ticket,
                "expiresAt", Instant.now().plusSeconds(60).toString(),
                "wsPath", "/api/agent/realtime/ws"
        ));
    }

    @GetMapping("/sessions")
    public Object listSessions(AgentActor actor) {
        List<Map<String, Object>> sessions = db.mysql.queryForList(
                "SELECT session_no, status, service_mode, active_handoff_no, last_active_time, created_time " +
                        "FROM t_agent_session WHERE user_id = ? ORDER BY last_active_time DESC LIMIT 20",
                actor.userId()
        );
        return ApiResponse.success(sessions);
    }

    @GetMapping("/sessions/{session}/snapshot")
    public Object snapshot(AgentActor actor, @PathVariable String session) {
        return ApiResponse.success(messageStore.getSessionSnapshot(session));
    }

    @GetMapping("/sessions/{session}/sync")
    public Object sync(AgentActor actor, @PathVariable String session,
                       @RequestParam(defaultValue = "0") long afterEventSeq,
                       @RequestParam(defaultValue = "100") int limit) {
        var events = eventStore.listEventsAfter(session, afterEventSeq, limit);
        long highWatermark = eventStore.getHighWatermark(session);
        return ApiResponse.success(Map.of(
                "events", events,
                "highWatermark", highWatermark,
                "hasMore", events.size() >= limit
        ));
    }

    @PostMapping("/sessions/{session}/read")
    public Object markRead(AgentActor actor, @PathVariable String session, @RequestBody Map<String, Object> body) {
        long seq = body.get("lastReadMessageSeq") != null ? ((Number) body.get("lastReadMessageSeq")).longValue() : 0L;
        conversationService.markRead(actor, session, seq);
        return ApiResponse.success(Map.of("marked", true));
    }

    @PostMapping("/sessions/{session}/handoffs")
    public Object requestHandoff(AgentActor actor, @PathVariable String session, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body != null && body.containsKey("reason") ? String.valueOf(body.get("reason")) : "用户请求人工客服";
        var res = handoffService.requestHandoff(actor, session, reason, "NORMAL", null, reason, null);
        return ApiResponse.success(res);
    }

    @PostMapping("/sessions/{session}/card-actions")
    public Object cardAction(AgentActor actor, @PathVariable String session, @RequestBody JsonNode body) {
        String cardId = body.path("cardId").asText("");
        String actionId = body.path("actionId").asText("");
        int revision = body.path("revision").asInt(1);
        String type = body.path("type").asText("");
        JsonNode input = body.path("input");
        String opId = body.path("operationId").asText("");

        var res = cardActionService.executeAction(actor, session, cardId, actionId, revision, type, input, opId);
        return ApiResponse.success(res);
    }
}
