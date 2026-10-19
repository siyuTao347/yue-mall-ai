package com.example.agent.customer.handoff;

import api.agent.AgentActor;
import api.response.ApiResponse;
import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.conversation.CustomerConversationService;
import com.example.agent.customer.copilot.CustomerCopilotService;
import com.example.agent.customer.realtime.RealtimeTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.example.agent.customer.CustomerJson.error;

@RestController
@RequestMapping("/api/staff/agent")
public class CustomerStaffController {

    private final CustomerDatabase db;
    private final CustomerHandoffService handoffService;
    private final RealtimeTicketService ticketService;
    private final StaffPresenceService presenceService;
    private final CustomerConversationService conversationService;
    private final CustomerCopilotService copilotService;

    public CustomerStaffController(CustomerDatabase db,
                                   CustomerHandoffService handoffService,
                                   RealtimeTicketService ticketService,
                                   StaffPresenceService presenceService,
                                   CustomerConversationService conversationService,
                                   CustomerCopilotService copilotService) {
        this.db = db;
        this.handoffService = handoffService;
        this.ticketService = ticketService;
        this.presenceService = presenceService;
        this.conversationService = conversationService;
        this.copilotService = copilotService;
    }

    private void requireStaff(AgentActor actor) {
        // 管理员或具备坐席档案的人员允许访问
        if ("ADMIN".equalsIgnoreCase(actor.role())) return;
        var rows = db.mysql.queryForList("SELECT id FROM t_agent_staff WHERE user_id = ? AND enabled = 1", actor.userId());
        if (rows.isEmpty()) throw error(403, "AGENT_PERMISSION_DENIED", "仅客服人员或管理员可访问");
    }

    @GetMapping("/me")
    public Object me(AgentActor actor) {
        requireStaff(actor);
        var rows = db.mysql.queryForList("SELECT * FROM t_agent_staff WHERE user_id = ?", actor.userId());
        if (rows.isEmpty()) {
            return ApiResponse.success(Map.of(
                    "userId", actor.userId(),
                    "staffId", "ST_" + actor.userId(),
                    "displayName", "客服人员",
                    "availability", "AVAILABLE",
                    "maxCapacity", 5,
                    "usedCapacity", 0
            ));
        }
        return ApiResponse.success(rows.getFirst());
    }

    @PutMapping("/me/availability")
    public Object updateAvailability(AgentActor actor, @RequestBody Map<String, Object> body) {
        requireStaff(actor);
        String availability = String.valueOf(body.getOrDefault("availability", "AVAILABLE"));
        presenceService.updateAvailability(actor.userId(), availability);
        return ApiResponse.success(Map.of("saved", true));
    }

    @PostMapping("/realtime/tickets")
    public Object createStaffTicket(AgentActor actor) {
        requireStaff(actor);
        String staffId = "ST_" + actor.userId();
        String ticket = ticketService.createTicket(actor.userId(), "STAFF", actor.role(), staffId);
        return ApiResponse.success(Map.of(
                "ticket", ticket,
                "expiresAt", Instant.now().plusSeconds(60).toString(),
                "wsPath", "/api/agent/realtime/ws"
        ));
    }

    @GetMapping("/handoffs")
    public Object listQueue(AgentActor actor,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long skillGroupId) {
        requireStaff(actor);
        return ApiResponse.success(handoffService.listQueue(status, skillGroupId));
    }

    @PostMapping("/handoffs/{no}/claim")
    public Object claim(AgentActor actor, @PathVariable String no, @RequestBody(required = false) Map<String, Object> body) {
        requireStaff(actor);
        String opId = body != null && body.containsKey("operationId") ? String.valueOf(body.get("operationId")) : null;
        handoffService.claimHandoff(actor.userId(), no, opId);
        return ApiResponse.success(Map.of("claimed", true));
    }

    @PostMapping("/handoffs/{no}/transfer")
    public Object transfer(AgentActor actor, @PathVariable String no, @RequestBody Map<String, Object> body) {
        requireStaff(actor);
        Long targetStaffId = body.containsKey("targetStaffUserId") ? ((Number) body.get("targetStaffUserId")).longValue() : null;
        Long targetGroupId = body.containsKey("targetGroupId") ? ((Number) body.get("targetGroupId")).longValue() : null;
        String reason = String.valueOf(body.getOrDefault("reason", "坐席转派"));
        handoffService.transferHandoff(actor.userId(), no, targetStaffId, targetGroupId, reason);
        return ApiResponse.success(Map.of("transferred", true));
    }

    @PostMapping("/handoffs/{no}/resolve")
    public Object resolve(AgentActor actor, @PathVariable String no, @RequestBody Map<String, Object> body) {
        requireStaff(actor);
        String disposition = String.valueOf(body.getOrDefault("disposition", "CLOSE"));
        String resolution = String.valueOf(body.getOrDefault("resolution", "已处理完成"));
        handoffService.resolveHandoff(actor.userId(), no, disposition, resolution);
        return ApiResponse.success(Map.of("resolved", true));
    }

    @PostMapping("/sessions/{session}/messages")
    public Object sendMessage(AgentActor actor, @PathVariable String session, @RequestBody Map<String, Object> body) {
        requireStaff(actor);
        String content = String.valueOf(body.get("content"));
        String clientMessageId = String.valueOf(body.get("clientMessageId"));
        Long expectedEpoch = body.containsKey("expectedControlEpoch") ? ((Number) body.get("expectedControlEpoch")).longValue() : null;
        var receipt = conversationService.sendMessage(actor, session, clientMessageId, content, expectedEpoch, "STAFF", null);
        return ApiResponse.success(receipt);
    }

    @PostMapping("/sessions/{session}/copilot/reply-suggestions")
    public Object replySuggestions(AgentActor actor, @PathVariable String session, @RequestBody(required = false) Map<String, Object> body) {
        requireStaff(actor);
        String instruction = body != null ? (String) body.get("instruction") : null;
        Long basedOnEventSeq = body != null && body.containsKey("basedOnEventSeq") ? ((Number) body.get("basedOnEventSeq")).longValue() : null;
        Long expectedEpoch = body != null && body.containsKey("expectedControlEpoch") ? ((Number) body.get("expectedControlEpoch")).longValue() : null;
        var result = copilotService.createReplySuggestions(actor, session, instruction, basedOnEventSeq, expectedEpoch);
        return ApiResponse.success(result);
    }

    @PostMapping("/sessions/{session}/copilot/summaries")
    public Object summaries(AgentActor actor, @PathVariable String session, @RequestBody(required = false) Map<String, Object> body) {
        requireStaff(actor);
        Long basedOnEventSeq = body != null && body.containsKey("basedOnEventSeq") ? ((Number) body.get("basedOnEventSeq")).longValue() : null;
        Long expectedEpoch = body != null && body.containsKey("expectedControlEpoch") ? ((Number) body.get("expectedControlEpoch")).longValue() : null;
        var result = copilotService.createSessionSummary(actor, session, basedOnEventSeq, expectedEpoch);
        return ApiResponse.success(result);
    }

    @GetMapping("/copilot/tasks/{taskNo}")
    public Object getCopilotTask(AgentActor actor, @PathVariable String taskNo) {
        requireStaff(actor);
        return ApiResponse.success(copilotService.getTask(taskNo));
    }
}
