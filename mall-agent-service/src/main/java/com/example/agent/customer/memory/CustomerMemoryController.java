package com.example.agent.customer.memory;

import api.agent.AgentActor;
import api.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class CustomerMemoryController {
    private final CustomerMemoryFacade facade;
    private final CustomerMemoryStore memoryStore;

    public CustomerMemoryController(CustomerMemoryFacade facade, CustomerMemoryStore memoryStore) {
        this.facade = facade;
        this.memoryStore = memoryStore;
    }

    public record SettingsRequest(boolean readEnabled, boolean writeEnabled, Long expectedRevision) {}
    public record PatchMemoryRequest(JsonNode value, JsonNode scope, Integer expectedVersion) {}

    @GetMapping("/memory/settings")
    public Object getSettings(AgentActor actor) {
        return ApiResponse.success(facade.getSettings(actor));
    }

    @PutMapping("/memory/settings")
    public Object updateSettings(AgentActor actor, @RequestBody SettingsRequest body) {
        return ApiResponse.success(facade.updateSettings(actor, body.readEnabled(), body.writeEnabled(), body.expectedRevision()));
    }

    @GetMapping("/memories")
    public Object listMemories(AgentActor actor, @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 50);
        return ApiResponse.success(facade.queryMemoriesForUser(actor, safeLimit));
    }

    @PatchMapping("/memories/{memoryNo}")
    public Object patchMemory(
        AgentActor actor,
        @PathVariable String memoryNo,
        @RequestHeader(value = "Idempotency-Key", required = false) String clientOpId,
        @RequestBody PatchMemoryRequest body
    ) {
        boolean success = facade.patchMemory(actor, memoryNo, body.value(), body.scope(), body.expectedVersion(), clientOpId);
        return ApiResponse.success(success);
    }

    @DeleteMapping("/memories/{memoryNo}")
    public Object deleteMemory(
        AgentActor actor,
        @PathVariable String memoryNo,
        @RequestHeader(value = "Idempotency-Key", required = false) String clientOpId
    ) {
        boolean success = facade.deleteMemory(actor, memoryNo, clientOpId);
        return ApiResponse.success(success);
    }

    @DeleteMapping("/memories")
    public Object clearAllMemories(
        AgentActor actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String clientOpId
    ) {
        boolean success = facade.clearAllMemories(actor, clientOpId);
        return ApiResponse.success(success);
    }

    @GetMapping("/memory/operations/{operationNo}")
    public Object getOperation(AgentActor actor, @PathVariable String operationNo) {
        return ApiResponse.success(memoryStore.getOperation(actor.userId(), operationNo));
    }
}
