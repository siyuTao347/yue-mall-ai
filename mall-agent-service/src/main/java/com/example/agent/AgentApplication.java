package com.example.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 阶段三 Agent 服务。
 * <p>本增量实现「管理员 RAG 知识库管理 + Embedding 入库」：
 * 文档 CRUD、父子分片、向量化任务、pgvector 写入与校验。
 * Agent 会话/编排、Tool Calling、检索融合（KEYWORD/HYBRID）在后续增量实现。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.example.agent.mapper")
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
