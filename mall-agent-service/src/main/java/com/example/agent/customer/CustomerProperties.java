package com.example.agent.customer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("customer-service")
public class CustomerProperties {
    private String provider="MOCK";
    private String apiKey="";
    private String masterModel="qwen-turbo";
    private String responseModel="qwen-plus";
    private int runTimeoutMs=90000;
    private int modelTimeoutMs=15000;
    private int retrievalTimeoutMs=12000;
    private int l1MaxChars=20;
    private boolean ragEnabled=true;
    private double knowledgeThreshold=0.5;
    private boolean preSaleEnabled=false;
    private int maxIntents=2;
}
