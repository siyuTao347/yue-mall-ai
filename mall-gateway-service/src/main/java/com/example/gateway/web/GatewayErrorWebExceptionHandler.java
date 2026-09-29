package com.example.gateway.web;

import com.example.gateway.exception.GatewayDependencyUnavailableException;
import com.example.gateway.exception.GatewayForbiddenException;
import com.example.gateway.exception.GatewayRateLimitedException;
import com.example.gateway.exception.GatewayUnauthorizedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GatewayErrorWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(exception);
        }

        HttpStatus status = resolveStatus(exception);
        String message = resolveMessage(status);
        logException(exchange, status, exception);
        return writeResponse(response, status, message);
    }

    private HttpStatus resolveStatus(Throwable exception) {
        if (exception instanceof GatewayUnauthorizedException) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (exception instanceof GatewayForbiddenException) {
            return HttpStatus.FORBIDDEN;
        }
        if (exception instanceof GatewayRateLimitedException) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (exception instanceof GatewayDependencyUnavailableException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (exception instanceof TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (exception instanceof ConnectException) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (exception instanceof ResponseStatusException responseException) {
            HttpStatus resolved = HttpStatus.resolve(responseException.getStatusCode().value());
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "请求格式错误";
            case UNAUTHORIZED -> "登录已失效，请重新登录";
            case FORBIDDEN -> "无权访问该资源";
            case NOT_FOUND -> "接口不存在";
            case TOO_MANY_REQUESTS -> "请求过于频繁，请稍后再试";
            case BAD_GATEWAY -> "服务暂时不可用";
            case SERVICE_UNAVAILABLE -> "服务暂时不可用，请稍后重试";
            case GATEWAY_TIMEOUT -> "服务响应超时，请稍后重试";
            default -> "系统繁忙，请稍后重试";
        };
    }

    private void logException(ServerWebExchange exchange, HttpStatus status, Throwable exception) {
        Object requestId = exchange.getAttribute(RequestTraceGlobalFilter.REQUEST_ID_ATTRIBUTE);
        String path = exchange.getRequest().getPath().value();
        if (status.is5xxServerError()) {
            log.error("Gateway request failed: requestId={}, path={}", requestId, path, exception);
        } else {
            log.warn(
                    "Gateway rejected request: requestId={}, path={}, status={}, exceptionType={}",
                    requestId,
                    path,
                    status.value(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private Mono<Void> writeResponse(ServerHttpResponse response, HttpStatus status, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl(CacheControl.noStore());

        try {
            byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse(status.value(), message));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }

    private record ErrorResponse(int code, String msg) {
    }
}
