package com.example.risk.web;

import api.context.TraceContext;
import api.exception.BizException;
import api.response.ApiResponse;
import api.response.ErrorCodes;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常处理：参数校验 400、业务异常按错误码映射 HTTP、未知异常统一 500，不返回堆栈。
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = firstValidationMessage(exception);
        log.warn("request parameter invalid: {}", message);
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.COMMON_REQUEST_PARAM_INVALID, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .orElse("请求参数非法");
        log.warn("request parameter invalid: {}", message);
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.COMMON_REQUEST_PARAM_INVALID, message);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        log.warn("request parameter invalid: {}", exception.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.COMMON_REQUEST_PARAM_INVALID, "请求参数非法");
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getHttpStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("business exception: errorCode={}, message={}",
                    exception.getErrorCode(), exception.getMessage(), exception);
        } else {
            log.warn("business exception: errorCode={}, message={}",
                    exception.getErrorCode(), exception.getMessage());
        }
        return build(status, exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("unexpected server error", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.COMMON_SYSTEM_ERROR, "系统繁忙，请稍后重试");
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), errorCode, message, TraceContext.getTraceId()));
    }

    private String firstValidationMessage(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::formatFieldError)
                .orElseGet(() -> exception.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage())
                        .orElse("请求参数非法"));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
