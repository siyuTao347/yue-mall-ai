package com.example.item.web;

import com.example.item.controller.MockPaymentController;
import com.example.item.dto.PaymentCallbackResponse;
import com.example.item.service.PaymentCallbackRejectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MockPaymentController.class)
public class PaymentCallbackControllerAdvice {

    @ExceptionHandler(PaymentCallbackRejectedException.class)
    public ResponseEntity<PaymentCallbackResponse> handleRejected(PaymentCallbackRejectedException exception) {
        return response(exception.status(), exception.code());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<PaymentCallbackResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "PAY_CALLBACK_PARAM_INVALID");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PaymentCallbackResponse> handleUnexpected(Exception exception) {
        log.error("payment callback processing failed", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "PAY_CALLBACK_INTERNAL_ERROR");
    }

    private ResponseEntity<PaymentCallbackResponse> response(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PaymentCallbackResponse(status.value(), code, null));
    }
}
