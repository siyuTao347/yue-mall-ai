package com.example.item.web;

import api.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final TraceContextFilter filter = new TraceContextFilter();

    @AfterEach
    void clearContext() {
        TraceContext.clear();
    }

    @Test
    void usesValidTraceIdHeaderAndClearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trade/orders");
        request.addHeader("X-Trace-Id", TRACE_ID.toUpperCase());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observed.set(TraceContext.getTraceId()));

        assertEquals(TRACE_ID, observed.get());
        assertEquals(TRACE_ID, response.getHeader("X-Request-Id"));
        assertNull(TraceContext.getTraceId());
    }

    @Test
    void fallsBackToTraceparentTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trade/orders");
        request.addHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observed.set(TraceContext.getTraceId()));

        assertEquals(TRACE_ID, observed.get());
    }

    @Test
    void fallsBackToUuidRequestId() throws Exception {
        String requestId = "8f14e45f-ea0b-4c1d-9d5f-2a1e2f43c9ab";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trade/orders");
        request.addHeader("X-Request-Id", requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observed.set(TraceContext.getTraceId()));

        assertEquals(requestId, observed.get());
    }

    @Test
    void rejectsIllegalTraceIdAndGeneratesUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trade/orders");
        request.addHeader("X-Trace-Id", "not-a-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> observed.set(TraceContext.getTraceId()));

        assertTrue(observed.get().matches(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"));
    }
}
