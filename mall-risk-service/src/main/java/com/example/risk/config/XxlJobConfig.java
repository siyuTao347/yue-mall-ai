package com.example.risk.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    private final String adminAddresses;
    private final String appname;
    private final int port;
    private final String logPath;
    private final int logRetentionDays;
    private final String accessToken;

    public XxlJobConfig(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.executor.appname}") String appname,
            @Value("${xxl.job.executor.port}") int port,
            @Value("${xxl.job.executor.logpath}") String logPath,
            @Value("${xxl.job.executor.logretentiondays}") int logRetentionDays,
            @Value("${xxl.job.accessToken}") String accessToken
    ) {
        this.adminAddresses = adminAddresses;
        this.appname = appname;
        this.port = port;
        this.logPath = logPath;
        this.logRetentionDays = logRetentionDays;
        this.accessToken = accessToken;
    }

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        executor.setAccessToken(accessToken);
        return executor;
    }
}
