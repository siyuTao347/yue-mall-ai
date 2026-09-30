package com.example.item.service;

import org.springframework.http.HttpStatus;

public class PaymentCallbackRejectedException extends RuntimeException {

    private final HttpStatus status;

    public PaymentCallbackRejectedException(String code, HttpStatus status) {
        super(code);
        this.status = status;
    }

    public String code() {
        return getMessage();
    }

    public HttpStatus status() {
        return status;
    }
}
