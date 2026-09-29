package com.example.risk.service;

import api.risk.RiskCommandDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RiskCommandDeadLetterService {
    private final RiskCommandService commandService;
    private final ObjectMapper objectMapper;

    public RiskCommandDeadLetterService(RiskCommandService commandService, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    public void handle(String message) {
        try {
            RiskCommandDTO command = objectMapper.readValue(message, RiskCommandDTO.class);
            commandService.markDeadLetter(command.getCommandNo());
        } catch (Exception e) {
            log.error("风控命令死信处理失败: message={}", message, e);
            throw new RuntimeException("风控命令死信处理失败", e);
        }
    }
}
