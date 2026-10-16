package com.example.item;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo
@ConfigurationPropertiesScan
@EnableScheduling
public class OrderApplication {
    static {
        System.setProperty("dubbo.network.interface.ignored", "utun.*");
        System.setProperty("dubbo.network.interface.preferred", "en0");
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
        System.out.println("====== 订单服务 (mall-order-service) 启动成功 ======");
    }
}
