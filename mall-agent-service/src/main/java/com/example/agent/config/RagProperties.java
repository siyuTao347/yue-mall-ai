package com.example.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 相关配置，对应设计文档 doc/agent/RAG/rag_development_design.md 第 3.3 / 16.2 节。
 * <p>注意：{@code rag.pgvector.datasource.*} 由向量数据源单独绑定，不在此类中声明字段，
 * Spring Boot 绑定器会忽略未匹配的字段。</p>
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private PgVector pgvector = new PgVector();
    private Admin admin = new Admin();

    @Data
    public static class Embedding {
        /** MOCK | OPENAI_COMPATIBLE */
        private String provider = "MOCK";
        private String model = "mock-hash-v1";
        private int dim = 1536;
        private int batchSize = 32;
        private int timeoutMs = 8000;
        private String baseUrl = "";
        private String apiKey = "";
        /** 单条向量化文本的最大字符数，超长截断 */
        private int maxTextChars = 8000;
    }

    @Data
    public static class Chunk {
        private Parent parent = new Parent();
        private Child child = new Child();
        /** 切片算法版本，参数变更后必须递增并全量重建 */
        private String splitterVersion = "v1";
        /** 关键词提取数量上限 */
        private int keywordLimit = 32;

        @Data
        public static class Parent {
            private int targetChars = 1000;
            private int maxChars = 1600;
            private int minChars = 400;
            /** 是否对父片也生成向量，默认关闭 */
            private boolean embedEnabled = false;
        }

        @Data
        public static class Child {
            private int targetChars = 320;
            private int maxChars = 450;
            private int minChars = 80;
            private int overlapChars = 60;
            private int maxPerParent = 8;
        }
    }

    @Data
    public static class Retrieval {
        /** KEYWORD | VECTOR | HYBRID */
        private String defaultMode = "VECTOR";
        private boolean fallbackEnabled = true;
        private int childTopK = 20;
        private int parentTopN = 5;
        private int rrfK = 60;
        private int maxContextChars = 6000;
    }

    @Data
    public static class PgVector {
        private int embeddingDim = 1536;
        private Index index = new Index();

        @Data
        public static class Index {
            private int hnswM = 16;
            private int hnswEfConstruction = 64;
            /** 查询期 hnsw.ef_search，通过 SET LOCAL 生效 */
            private int searchEf = 100;
        }
    }

    @Data
    public static class Admin {
        /** 文档正文敏感信息命中策略：REJECT | MASK */
        private String sensitiveAction = "REJECT";
        /** 单次批量写库条数 */
        private int batchInsertSize = 200;
    }
}
