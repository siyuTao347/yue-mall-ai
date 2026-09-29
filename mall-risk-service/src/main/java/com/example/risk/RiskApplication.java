package com.example.risk;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo
@EnableScheduling
@MapperScan("com.example.risk.mapper")
public class RiskApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
