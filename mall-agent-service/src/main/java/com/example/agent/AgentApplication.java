package com.example.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Knowledge management and the customer-service Graph, tools and release lifecycle. */
@org.apache.dubbo.config.spring.context.annotation.EnableDubbo
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.example.agent.mapper")
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
