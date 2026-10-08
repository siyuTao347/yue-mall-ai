package com.example.item.web;

import api.context.TraceContext;
import api.exception.BadRequestException;
import api.exception.StateConflictException;
import api.response.ApiResponse;
import api.response.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void mapsBizExceptionToHttpStatusAndErrorCode() {
        TraceContext.set("trace-abc");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBiz(
                new StateConflictException("TRADE_ORDER_STATE_INVALID", "订单状态不允许操作"));

        assertEquals(409, response.getStatusCode().value());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals("TRADE_ORDER_STATE_INVALID", body.errorCode());
        assertEquals("trace-abc", body.traceId());
        assertNull(body.data());
    }

    @Test
    void mapsValidationErrorToBadRequest() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "itemId", "商品不能为空"));
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(exception);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(ErrorCodes.COMMON_REQUEST_PARAM_INVALID, response.getBody().errorCode());
    }

    @Test
    void mapsBadRequestException() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBiz(new BadRequestException("参数不合法"));

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(ErrorCodes.COMMON_REQUEST_PARAM_INVALID, response.getBody().errorCode());
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(new RuntimeException("jdbc:mysql://internal-host password=secret"));

        assertEquals(500, response.getStatusCode().value());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(ErrorCodes.COMMON_SYSTEM_ERROR, body.errorCode());
        assertEquals("系统繁忙，请稍后重试", body.msg());
        assertNull(body.data());
    }
}
