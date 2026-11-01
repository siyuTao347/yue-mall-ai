package com.example.moneyagent.api;

import api.agent.money.MoneySecuritySupport;
import api.response.ApiResponse;
import com.example.moneyagent.application.MoneyGrantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内部资金授权受控管理接口
 * <p>
 * 提供 Grant 的主动撤回 (Revocation)，要求调用方提供经过验证的服务凭据
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/money/grants")
public class MoneyInternalGrantController {

    private final MoneyGrantService grantService;

    public MoneyInternalGrantController(MoneyGrantService grantService) {
        this.grantService = grantService;
    }

    @PostMapping("/{grantNo}/revoke")
    public ApiResponse<MoneyGrantService.GrantRevocationResult> revokeGrant(
            @PathVariable String grantNo,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> req) {

        // 校验调用方服务身份
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            MoneySecuritySupport.ServiceIdentityClaims claims =
                    MoneySecuritySupport.verifyAndParseServiceIdentity(token, null, "mall-money-services");
            if (claims == null) {
                return ApiResponse.error(401, "UNAUTHORIZED", "无效或未授权的服务身份凭据");
            }
        }

        String reason = req != null && req.containsKey("reason") ? (String) req.get("reason") : "管理撤回";
        Long expectedVersion = null;
        if (req != null && req.containsKey("expectedGrantVersion")) {
            Object v = req.get("expectedGrantVersion");
            if (v instanceof Number n) {
                expectedVersion = n.longValue();
            }
        }

        MoneyGrantService.GrantRevocationResult result = grantService.revokeGrant(grantNo, reason, expectedVersion);
        if (!result.success()) {
            return ApiResponse.error(400, "REVOKE_FAILED", result.message());
        }

        return ApiResponse.success(result);
    }
}

