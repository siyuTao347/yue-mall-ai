package com.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 向量化任务线程池：入库耗时受模型限速影响，必须异步执行，避免阻塞管理端请求。 */
@Configuration
public class RagExecutorConfig {

    @Bean("ragIngestExecutor")
    public ThreadPoolTaskExecutor ragIngestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 向量清理线程池：文档删除后的 pgvector 清理不再占用请求线程。
     * <p>独立于入库线程池，避免被长时入库任务排在后面导致向量长期残留。</p>
     */
    @Bean("ragCleanupExecutor")
    public ThreadPoolTaskExecutor ragCleanupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("rag-cleanup-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
