package com.example.risk;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
@MapperScan("com.example.risk.mapper")
public class RiskApplication {
    static {
        System.setProperty("dubbo.network.interface.ignored", "utun.*");
        System.setProperty("dubbo.network.interface.preferred", "en0");
    }

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
