package com.example.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.user.mapper")
public class UserApplication {
    static {
        System.setProperty("dubbo.network.interface.ignored", "utun.*");
        System.setProperty("dubbo.network.interface.preferred", "en0");
    }

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
        System.out.println("====== 用户服务及积分资产中心启动成功 ======");
    }
}
