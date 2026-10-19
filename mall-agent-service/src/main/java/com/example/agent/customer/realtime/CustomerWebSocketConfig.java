package com.example.agent.customer.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
public class CustomerWebSocketConfig implements WebSocketConfigurer {

    private final CustomerWebSocketHandler webSocketHandler;
    private final RealtimeTicketService ticketService;

    public CustomerWebSocketConfig(CustomerWebSocketHandler webSocketHandler, RealtimeTicketService ticketService) {
        this.webSocketHandler = webSocketHandler;
        this.ticketService = ticketService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/api/agent/realtime/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                  WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            String ticket = servletRequest.getServletRequest().getParameter("ticket");
                            RealtimePrincipal principal = ticketService.consumeTicket(ticket);
                            if (principal != null) {
                                attributes.put("principal", principal);
                                return true;
                            }
                        }
                        return false; // 拒绝无效或过期票据握手
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                    }
                });
    }
}
