package com.example.risk.controller;

import api.util.JwtUtil;
import com.example.risk.entity.RiskCase;
import com.example.risk.service.RiskCommandService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/risk")
public class RiskCaseAdminController {
    private final RiskCommandService riskCommandService;

    public RiskCaseAdminController(RiskCommandService riskCommandService) {
        this.riskCommandService = riskCommandService;
    }

    @GetMapping("/cases")
    public Map<String, Object> listCases(@RequestHeader(value = "Authorization", required = false) String token,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        return response(200, "success", riskCommandService.listCases(status, page, size));
    }

    @GetMapping("/cases/{caseNo}")
    public Map<String, Object> detail(@RequestHeader(value = "Authorization", required = false) String token,
                                      @PathVariable String caseNo) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        try {
            return response(200, "success", riskCommandService.detail(caseNo));
        } catch (Exception e) {
            return response(404, e.getMessage(), null);
        }
    }

    @PostMapping("/cases/{caseNo}/assign")
    public Map<String, Object> assign(@RequestHeader(value = "Authorization", required = false) String token,
                                      @PathVariable String caseNo) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        try {
            return response(200, "案件认领成功",
                    riskCommandService.assign(caseNo, operator.id(), operator.name()));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/cases/{caseNo}/resolve")
    public Map<String, Object> resolve(@RequestHeader(value = "Authorization", required = false) String token,
                                       @PathVariable String caseNo,
                                       @RequestBody Map<String, Object> body) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        String command = text(body.get("command"));
        String reason = text(body.get("reason"));
        if (command.isBlank() || reason.isBlank()) {
            return response(400, "处理命令和原因不能为空", null);
        }
        try {
            RiskCase riskCase = riskCommandService.resolve(caseNo, operator.id(), operator.name(),
                    command, reason, actionParams(body));
            return response(200, "案件处理完成", riskCase);
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/cases/{caseNo}/close")
    public Map<String, Object> close(@RequestHeader(value = "Authorization", required = false) String token,
                                     @PathVariable String caseNo,
                                     @RequestBody Map<String, String> body) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        try {
            return response(200, "案件已关闭",
                    riskCommandService.close(caseNo, operator.id(), operator.name(), text(body.get("reason"))));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/cases/{caseNo}/reopen")
    public Map<String, Object> reopen(@RequestHeader(value = "Authorization", required = false) String token,
                                      @PathVariable String caseNo,
                                      @RequestBody Map<String, String> body) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        try {
            return response(200, "案件已重开",
                    riskCommandService.reopen(caseNo, operator.id(), operator.name(), text(body.get("reason"))));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @PostMapping("/cases/{caseNo}/commands/{commandNo}/resend")
    public Map<String, Object> resend(@RequestHeader(value = "Authorization", required = false) String token,
                                      @PathVariable String caseNo,
                                      @PathVariable String commandNo) {
        Operator operator = requireAdmin(token);
        if (operator == null) {
            return response(403, "无管理员权限", null);
        }
        try {
            return response(200, "风控命令已重发",
                    riskCommandService.resend(caseNo, commandNo, operator.id(), operator.name()));
        } catch (Exception e) {
            return response(400, e.getMessage(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> actionParams(Map<String, Object> body) {
        Object value = body.get("actionParams");
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private Operator requireAdmin(String token) {
        Long userId = JwtUtil.parseUserId(token);
        if (userId == null || !"ADMIN".equals(JwtUtil.parseRole(token))) {
            return null;
        }
        JsonNode payload = JwtUtil.parsePayload(token);
        String nickname = payload == null ? null : payload.path("nickname").asText(null);
        return new Operator(userId, nickname == null || nickname.isBlank()
                ? "ADMIN-" + userId : nickname);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", message);
        result.put("data", data);
        return result;
    }

    private record Operator(Long id, String name) {
    }
}
