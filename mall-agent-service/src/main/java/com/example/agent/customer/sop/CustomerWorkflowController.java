package com.example.agent.customer.sop;

import api.agent.AgentActor;
import api.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/workflows")
public class CustomerWorkflowController {

    private final CustomerSopEngine sopEngine;

    public CustomerWorkflowController(CustomerSopEngine sopEngine) {
        this.sopEngine = sopEngine;
    }

    @PostMapping
    public Object start(AgentActor actor, @RequestBody JsonNode body) {
        String workflowCode = body.path("workflowCode").asText("AFTER_SALE_REVIEW");
        String sessionNo = body.path("sessionNo").asText("");
        String bizType = body.path("bizType").asText("ORDER");
        String bizNo = body.path("bizNo").asText("");
        JsonNode vars = body.path("variables");

        var result = sopEngine.startWorkflow(actor, workflowCode, sessionNo, bizType, bizNo, vars);
        return ApiResponse.success(result);
    }

    @GetMapping
    public Object list(AgentActor actor) {
        return ApiResponse.success(sopEngine.listUserWorkflows(actor.userId()));
    }

    @PostMapping("/{workflowNo}/tasks/{taskNo}/submit")
    public Object submitTask(AgentActor actor,
                             @PathVariable String workflowNo,
                             @PathVariable String taskNo,
                             @RequestBody JsonNode body) {
        JsonNode values = body.path("values");
        String opId = body.path("operationId").asText("");
        var result = sopEngine.submitTask(actor, workflowNo, taskNo, values, opId);
        return ApiResponse.success(result);
    }
}
